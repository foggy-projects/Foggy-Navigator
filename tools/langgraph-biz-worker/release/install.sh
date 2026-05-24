#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${LANGGRAPH_BIZ_WORKER_HOME:-$HOME/.langgraph-biz-worker}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Installing LangGraph BizWorker to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete \
    --exclude ".env" \
    --exclude ".venv" \
    "$SOURCE_DIR/" "$INSTALL_DIR/"
else
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf "$tmp_dir"' EXIT
  tar cf "$tmp_dir/package.tar" -C "$SOURCE_DIR" --exclude .env --exclude .venv .
  tar xf "$tmp_dir/package.tar" -C "$INSTALL_DIR"
fi

cd "$INSTALL_DIR"

if [ ! -f ".env" ]; then
  cp .env.example .env
fi

if ! grep -q '^BIZ_WORKER_ENABLE_COMMAND=' .env; then
  printf '\nBIZ_WORKER_ENABLE_COMMAND=true\n' >> .env
fi

python_bin="${PYTHON:-python3}"
if ! command -v "$python_bin" >/dev/null 2>&1; then
  echo "Python 3 was not found. Install Python >=3.10, then run: $python_bin -m venv .venv && . .venv/bin/activate && pip install -e ."
  exit 1
fi

"$python_bin" -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e .

echo ""
echo "LangGraph BizWorker installed."
echo "Edit $INSTALL_DIR/.env with LLM settings, then start it with:"
echo "  cd $INSTALL_DIR"
echo "  . .venv/bin/activate"
echo "  BIZ_WORKER_ENV_FILE=.env uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port 3065"
