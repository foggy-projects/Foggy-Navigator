#!/usr/bin/env python
# -*- coding: utf-8 -*-
import urllib.request
import urllib.parse
import time
import json

BASE_URL = "http://localhost:8112"

def api_call(method, path, headers=None, data=None, timeout=10):
    url = f"{BASE_URL}{path}"
    if headers is None:
        headers = {}
    headers["Content-Type"] = "application/json; charset=utf-8"

    if data:
        data = json.dumps(data).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"  WARN  API call failed: {e}")
        return None

print("=" * 50)
print("  Memory E2E Test")
print("=" * 50)
print()

# 1. Login
print("[1/5] Logging in...")
resp = api_call("POST", "/api/v1/auth/login", data={"username": "root", "password": "root123"})
token = resp["data"]["token"]
print(f"  OK Token: {token[:20]}...")

headers = {"Authorization": f"Bearer {token}"}

# 2. Create session and ask to save memory
print()
print("[2/5] Creating session and asking to save memory...")
resp = api_call("POST", "/api/v1/sessions", headers=headers, data={"agentId": "tutor-agent"})
session1 = resp["data"]["id"]
print(f"  OK Session: {session1}")

resp = api_call("POST", f"/api/v1/sessions/{session1}/messages",
    headers=headers, data={"content": "请记住我最喜欢的编程语言是Rust"}, timeout=60)

print("  WAIT Waiting for agent (15s)...")
time.sleep(15)

# Check logs
with open("D:/foggy-projects/Foggy-Navigator/logs/backend.log", "r", encoding="utf-8", errors="ignore") as f:
    log_content = f.read()
    if "Memory saved via tool" in log_content and "Rust" in log_content:
        print("  OK save_memory was called")
    else:
        print("  FAIL save_memory was NOT called")
        if "invalid_api_key" in log_content or "Incorrect API key" in log_content:
            print("    WARN  API key error detected")

# 3. List memories
print()
print("[3/5] Listing memories...")
resp = api_call("GET", "/api/v1/config/platform/memories", headers=headers)
memories = resp["data"]
for m in memories:
    print(f"  - [{m['category']}] {m['content']}")

# 4. Create new session and check memory injection
print()
print("[4/5] Testing memory injection in new session...")
resp = api_call("POST", "/api/v1/sessions", headers=headers, data={"agentId": "tutor-agent"})
session2 = resp["data"]["id"]

resp = api_call("POST", f"/api/v1/sessions/{session2}/messages",
    headers=headers, data={"content": "你记得我喜欢什么编程语言吗"}, timeout=60)

time.sleep(10)

with open("D:/foggy-projects/Foggy-Navigator/logs/backend.log", "r", encoding="utf-8", errors="ignore") as f:
    lines = f.readlines()
    injected_lines = [l for l in lines if "Injected user memory" in l]
    if injected_lines:
        last = injected_lines[-1].strip()
        print(f"  OK Memory injected: {last[last.find('Injected'):last.find('Injected')+60]}...")
    else:
        print("  FAIL Memory was NOT injected")

# 5. Test list_memory tool
print()
print("[5/5] Testing list_memory tool...")
resp = api_call("POST", "/api/v1/sessions", headers=headers, data={"agentId": "tutor-agent"})
session3 = resp["data"]["id"]

resp = api_call("POST", f"/api/v1/sessions/{session3}/messages",
    headers=headers, data={"content": "列出我的所有记忆"}, timeout=60)

time.sleep(10)

with open("D:/foggy-projects/Foggy-Navigator/logs/backend.log", "r", encoding="utf-8", errors="ignore") as f:
    log_content = f.read()
    if "Listed" in log_content and "memories for userId=" in log_content:
        print("  OK list_memory was called")
    else:
        print("  FAIL list_memory was NOT called")

print()
print("=" * 50)
print("  Test Complete")
print("=" * 50)
