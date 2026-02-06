#!/bin/bash
# Mock LLM Service 停止脚本 (Linux/Mac)

SERVICE_NAME="mock-llm-service"

echo "[INFO] Stopping Mock LLM Service..."

if docker ps -aq -f "name=$SERVICE_NAME" | grep -q .; then
    docker stop $SERVICE_NAME
    docker rm $SERVICE_NAME
    echo "[OK] Service stopped"
else
    echo "[INFO] Service is not running"
fi
