---
name: worker-env-setup
description: Remote automated setup of Claude Worker development environment on Linux/Mac machines via SSH. Installs Worker, Claude Code CLI, configures .env, verifies health. Triggers: /worker-setup, /setup-worker, mentions "install worker env", "setup worker", "deploy worker to", "install worker on".
---

# Claude Worker Environment — Remote Automated Setup

Automate the full installation and configuration of Claude Agent Worker on a remote Linux/Mac machine via SSH.

## When to Use

When the user asks to set up a Worker environment on a remote machine, e.g.:
- "为 192.168.31.68, SSH root/password, 安装 worker 环境"
- "Setup worker on 10.0.1.5, user=deploy, password=xxx"
- "在刘方晓的机器上安装 worker，IP 192.168.31.70"

## Input Parsing

Extract these parameters from the user's message:

| Parameter | Required | Example | Default |
|-----------|----------|---------|---------|
| `HOST` | ✅ | `192.168.31.68` | - |
| `SSH_USER` | ✅ | `root` | - |
| `SSH_PASSWORD` | ✅ | `password123` | - |
| `SSH_PORT` | ❌ | `22` | `22` |
| `WORKER_TOKEN` | ❌ | `my-secret-token` | Auto-generate 32-char hex |
| `WORKER_NAME` | ❌ | `dev-machine-01` | Remote hostname |
| `ALLOWED_CWDS` | ❌ | `["/home","/opt"]` | `["/home"]` |
| `WORKER_PORT` | ❌ | `3031` | `3031` |
| `INSTALL_PROXY` | ❌ | Whether to also install Claude Code Proxy | No |

If a required parameter is missing, ask the user before proceeding.

## SSH Execution Pattern

All remote commands use `sshpass` + `ssh` via the Bash tool:

```bash
# Helper function — define once, reuse throughout
REMOTE_CMD="sshpass -p 'SSH_PASSWORD' ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p SSH_PORT SSH_USER@HOST"

# Single command
$REMOTE_CMD 'command here'

# Multi-line script
sshpass -p 'SSH_PASSWORD' ssh -o StrictHostKeyChecking=no -p SSH_PORT SSH_USER@HOST 'bash -s' <<'REMOTE_EOF'
set -e
command1
command2
REMOTE_EOF
```

**If `sshpass` is not available locally**, install it first:
```bash
# macOS
brew install sshpass 2>/dev/null || brew install hudochenkov/sshpass/sshpass

# Ubuntu/Debian (local machine)
sudo apt-get install -y sshpass

# CentOS/RHEL
sudo yum install -y sshpass
```

## Execution Steps

### Step 1: Test SSH Connectivity

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p PORT USER@HOST 'echo "SSH_OK"'
```

**If fails**: Report the error (connection refused, auth failed, timeout) and stop.

### Step 2: Detect Remote Environment

Run a single SSH command to gather all info at once:

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST 'bash -s' <<'REMOTE_EOF'
echo "=== OS ==="
uname -a
echo "=== HOSTNAME ==="
hostname
echo "=== PYTHON ==="
python3 --version 2>&1 || echo "NOT_FOUND"
echo "=== NODE ==="
node --version 2>&1 || echo "NOT_FOUND"
echo "=== NPM ==="
npm --version 2>&1 || echo "NOT_FOUND"
echo "=== CLAUDE_CLI ==="
claude --version 2>&1 || echo "NOT_FOUND"
echo "=== EXISTING_WORKER ==="
cat ~/.claude-worker/VERSION 2>/dev/null || echo "NOT_INSTALLED"
echo "=== PKG_MANAGER ==="
if command -v apt-get &>/dev/null; then echo "apt";
elif command -v yum &>/dev/null; then echo "yum";
elif command -v dnf &>/dev/null; then echo "dnf";
elif command -v brew &>/dev/null; then echo "brew";
else echo "unknown"; fi
REMOTE_EOF
```

Parse the output and decide what needs to be installed.

**If Worker is already installed**: Inform the user. Ask if they want to upgrade/reinstall, or skip to config-only.

### Step 3: Install Prerequisites

Based on Step 2 results, install what's missing. Run as a single SSH session:

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST 'bash -s' <<'REMOTE_EOF'
set -e

# --- Python 3.10+ ---
PYTHON_OK=false
if command -v python3 &>/dev/null; then
    PY_VER=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    PY_MAJOR=$(echo $PY_VER | cut -d. -f1)
    PY_MINOR=$(echo $PY_VER | cut -d. -f2)
    if [ "$PY_MAJOR" -ge 3 ] && [ "$PY_MINOR" -ge 10 ]; then
        PYTHON_OK=true
        echo "Python $PY_VER OK"
    fi
fi

if [ "$PYTHON_OK" = false ]; then
    echo "Installing Python..."
    if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq
        sudo apt-get install -y python3 python3-pip python3-venv
    elif command -v yum &>/dev/null; then
        sudo yum install -y python3 python3-pip
    elif command -v dnf &>/dev/null; then
        sudo dnf install -y python3 python3-pip
    fi
fi

# --- python3-venv (Debian/Ubuntu may need separate package) ---
if command -v apt-get &>/dev/null; then
    PY_VER=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    sudo apt-get install -y python3.${PY_VER##*.}-venv 2>/dev/null || sudo apt-get install -y python3-venv 2>/dev/null || true
fi

# --- Node.js 18+ ---
NODE_OK=false
if command -v node &>/dev/null; then
    NODE_MAJOR=$(node --version | sed 's/v//' | cut -d. -f1)
    if [ "$NODE_MAJOR" -ge 18 ]; then
        NODE_OK=true
        echo "Node.js $(node --version) OK"
    fi
fi

if [ "$NODE_OK" = false ]; then
    echo "Installing Node.js 18..."
    if command -v apt-get &>/dev/null; then
        curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
        sudo apt-get install -y nodejs
    elif command -v yum &>/dev/null || command -v dnf &>/dev/null; then
        curl -fsSL https://rpm.nodesource.com/setup_18.x | sudo bash -
        sudo yum install -y nodejs 2>/dev/null || sudo dnf install -y nodejs
    fi
fi

# --- Claude Code CLI ---
if ! command -v claude &>/dev/null; then
    echo "Installing Claude Code CLI..."
    sudo npm install -g @anthropic-ai/claude-code
fi

echo "=== Prerequisites Done ==="
python3 --version
node --version
claude --version 2>/dev/null || echo "claude CLI: installed (may need PATH refresh)"
REMOTE_EOF
```

**If any prerequisite fails to install**: Report the specific error and stop. Do NOT continue with a broken environment.

### Step 4: Install Worker

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST \
  'curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/install.sh | bash'
```

Verify installation succeeded by checking exit code and output for "installed!" or "upgraded to".

### Step 5: Configure .env

Generate the token if not provided, then write the configuration:

```bash
# Generate token locally if needed
WORKER_TOKEN="${USER_PROVIDED_TOKEN:-$(openssl rand -hex 16)}"

sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST 'bash -s' <<REMOTE_EOF
set -e
ENV_FILE=~/.claude-worker/.env

# Read current .env as base
# Then overwrite specific keys using sed or full rewrite

cat > \$ENV_FILE <<'ENVEOF'
# Claude Agent Worker - Configuration
# Auto-configured by worker-env-setup skill

# Worker basic settings
AGENT_WORKER_PORT=${WORKER_PORT}
AGENT_WORKER_HOST=0.0.0.0
AGENT_WORKER_WORKER_NAME=${WORKER_NAME}
AGENT_WORKER_WORKER_TOKEN=${WORKER_TOKEN}

# Allowed working directories
AGENT_WORKER_ALLOWED_CWDS=${ALLOWED_CWDS}

# Max concurrent tasks
AGENT_WORKER_MAX_CONCURRENT_TASKS=5

# Auto-upgrade URL
CLAUDE_WORKER_URL=https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker

# LLM config: use 'claude login' on this machine for subscription mode
# Or uncomment below for proxy/API key mode:
# AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=sk-xxx
# AGENT_WORKER_ANTHROPIC_BASE_URL=http://localhost:8082
ENVEOF

echo ".env configured successfully"
cat \$ENV_FILE
REMOTE_EOF
```

**Variable substitution**: The values `${WORKER_PORT}`, `${WORKER_NAME}`, `${WORKER_TOKEN}`, `${ALLOWED_CWDS}` should be replaced with actual values before sending the SSH command. Use Bash variable expansion or construct the command string directly.

### Step 6: Add PATH

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST 'bash -s' <<'REMOTE_EOF'
WORKER_BIN="$HOME/.claude-worker/bin"
PATH_LINE="export PATH=\"$WORKER_BIN:\$PATH\""

# Add to .bashrc if not already present
if ! grep -qF ".claude-worker/bin" ~/.bashrc 2>/dev/null; then
    echo "" >> ~/.bashrc
    echo "# Claude Agent Worker" >> ~/.bashrc
    echo "$PATH_LINE" >> ~/.bashrc
    echo "PATH added to ~/.bashrc"
fi

# Also add to .profile for login shells
if ! grep -qF ".claude-worker/bin" ~/.profile 2>/dev/null; then
    echo "" >> ~/.profile
    echo "# Claude Agent Worker" >> ~/.profile
    echo "$PATH_LINE" >> ~/.profile
fi

# Export for current session
export PATH="$WORKER_BIN:$PATH"
echo "PATH configured: $WORKER_BIN"
REMOTE_EOF
```

### Step 7: Start and Verify

```bash
sshpass -p 'PASSWORD' ssh -o StrictHostKeyChecking=no -p PORT USER@HOST 'bash -s' <<'REMOTE_EOF'
export PATH="$HOME/.claude-worker/bin:$PATH"

# Start the worker
claude-worker start
sleep 3

# Verify
echo "=== STATUS ==="
claude-worker status

echo "=== VERSION ==="
claude-worker version

echo "=== HEALTH ==="
curl -sf http://localhost:${WORKER_PORT:-3031}/health 2>&1 || echo "HEALTH_CHECK_FAILED"

echo "=== PROCESS ==="
ps aux | grep -v grep | grep agent_worker || echo "NO_PROCESS"
REMOTE_EOF
```

**If health check fails**: Check the error log:
```bash
$REMOTE_CMD 'cat ~/.claude-worker/logs/worker-error.log 2>/dev/null | tail -20'
```

### Step 8: Output Installation Report

After all steps succeed, output a formatted report:

```
╔══════════════════════════════════════════════════════════════╗
║           Claude Agent Worker — Installation Report          ║
╠══════════════════════════════════════════════════════════════╣
║ Host:           192.168.31.68                                ║
║ OS:             Ubuntu 22.04 (Linux 5.15.0)                  ║
║ Hostname:       dev-kvm-02                                   ║
║ Worker Version: 0.1.1                                        ║
║ Port:           3031                                         ║
║ Status:         RUNNING ✓                                    ║
║ Health Check:   OK ✓                                         ║
╠══════════════════════════════════════════════════════════════╣
║ Worker Name:    dev-kvm-02                                   ║
║ Worker Token:   a1b2c3d4e5f6...  (32 chars)                 ║
║ Allowed CWDs:   ["/home"]                                    ║
║ Upgrade URL:    https://obs-fe55.obs.../claude-worker        ║
╠══════════════════════════════════════════════════════════════╣
║ NEXT STEPS:                                                  ║
║ 1. Register this worker in Navigator platform:               ║
║    - Name: dev-kvm-02                                        ║
║    - URL:  http://192.168.31.68:3031                         ║
║    - Token: a1b2c3d4e5f6...                                  ║
║ 2. Run 'claude login' on the machine for LLM access          ║
║    (or configure API key / proxy in .env)                     ║
╚══════════════════════════════════════════════════════════════╝
```

## Optional: Also Install Claude Code Proxy

If the user requests `INSTALL_PROXY=true` or mentions "also install proxy" / "也装 proxy":

Insert between Step 4 and Step 5:

```bash
$REMOTE_CMD 'curl -sSL https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-code-proxy/install.sh | bash'
```

Then in Step 5, also configure the Worker's LLM to point at the local proxy:
```env
AGENT_WORKER_ANTHROPIC_AUTH_TOKEN=any-token
AGENT_WORKER_ANTHROPIC_BASE_URL=http://localhost:8082
```

And remind the user to configure the proxy's `.env` (API key, model mapping).

## Error Handling

| Error | Cause | Action |
|-------|-------|--------|
| `ssh: connect to host ... port 22: Connection refused` | SSH not running or wrong port | Check if sshd is running, verify port |
| `Permission denied (publickey,password)` | Wrong credentials | Ask user to verify username/password |
| `python3: command not found` after install | Package manager failed | Show error, suggest manual install |
| `npm: command not found` | Node.js not installed properly | Retry with different install method |
| `Worker failed to start within 30 seconds` | Port conflict or Python error | Check `claude-worker logs` and error log |
| `HEALTH_CHECK_FAILED` | Worker crashed after start | Read `~/.claude-worker/logs/worker-error.log` |
| `.venv/bin/pip: No such file or directory` | Broken venv from previous attempt | `rm -rf ~/.claude-worker/.venv` and reinstall |

## Batch Installation

For installing on multiple machines, the user can provide a list:

> Install worker on these machines:
> - 192.168.31.68 root/pass1
> - 192.168.31.70 root/pass2
> - 192.168.31.72 deploy/pass3

Execute the steps for each machine sequentially (or in parallel using background Bash tasks), using the same token or generating unique tokens per machine. Output a summary table at the end.

## Security Notes

1. **Passwords in shell history**: The `sshpass` commands will appear in the local shell history. This is acceptable for internal/VPN environments.
2. **Worker tokens**: If auto-generated, record them in the installation report — they're needed to register the worker in Navigator.
3. **SSH key auth**: If the user provides a key path instead of password, use `ssh -i /path/to/key` instead of `sshpass`.
