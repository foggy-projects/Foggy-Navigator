---
name: process-detection-dev
description: 进程检测模块开发指导（策略模式扩展、平台 Detector 实现、验证与测试）。当用户需要新增平台检测器、修改检测 pattern、调试进程检测失败、或生成检测测试脚本时使用。触发词：/process-detection, /pd, 提及"进程检测"、"ProcessDetector"、"Detector"、"CLI进程"。
---

# 进程检测模块开发指导

指导开发者扩展 `tools/claude-agent-worker/src/agent_worker/claude/process_detection.py` 模块——基于策略模式的跨平台 Claude CLI 进程检测。

## 使用场景

- 新增平台检测器（如 MacOSDetector）
- 修改现有检测 pattern（匹配新版 CLI 命令行签名）
- 调试进程检测不生效（找不到 CLI 进程）
- 生成验证/测试脚本确认检测逻辑正确

## 架构概览

```
process_detection.py
├── ProcessInfo          # @dataclass — OS 层原始进程信息
├── _DetectionPattern    # @dataclass — 检测模式（description + query）
├── ProcessDetector      # Protocol — find_pids() + get_details()
├── WindowsDetector      # PowerShell Get-CimInstance
├── UnixDetector         # pgrep -f + ps
└── get_detector()       # 工厂函数，返回平台单例
```

### 调用链

```
routes/processes.py::list_processes()
  → _find_sdk_cli_pids()           # sdk_wrapper.py shim
    → get_detector().find_pids()   # 策略分发
  → _get_process_details(pids)
    → get_detector().get_details() # 策略分发
    → ProcessInfo → CliProcessInfo # 转换（加 is_orphan 等业务字段）
  → _enrich_processes()            # 补充 foggy_task_id 等
```

### 关键文件

| 文件 | 职责 |
|------|------|
| `claude/process_detection.py` | 策略模式核心：Protocol + Detector 实现 |
| `claude/sdk_wrapper.py` | `_find_sdk_cli_pids()` shim，供 query.py 和 finally 块调用 |
| `routes/processes.py` | `_get_process_details()` converter + REST 端点 |
| `models.py` | `CliProcessInfo` Pydantic model（含 is_orphan 等业务字段） |

## 执行流程

### 1. 新增平台 Detector

```python
# 示例：MacOSDetector 继承 UnixDetector 覆盖 pattern
class MacOSDetector(UnixDetector):
    def __init__(self) -> None:
        super().__init__()
        # macOS 上 Claude Code 可能进程名不同
        self.patterns = [
            _DetectionPattern(
                description="macOS Claude agent-sdk",
                query=r"claude.*--output-format.*stream-json",
            ),
            _DetectionPattern(
                description="macOS Claude code-sdk legacy",
                query=r"claude-code.*cli\.js.*--print",
            ),
        ]
```

然后更新 `get_detector()` 工厂：

```python
def get_detector() -> ProcessDetector:
    global _detector
    if _detector is None:
        if os.name == "nt":
            _detector = WindowsDetector()
        elif sys.platform == "darwin":
            _detector = MacOSDetector()
        else:
            _detector = UnixDetector()
    return _detector
```

### 2. 修改检测 Pattern

- **Windows**: 修改 `WindowsDetector.__init__` 中的 `_DetectionPattern.query`（PowerShell Where-Object 语法）
- **Unix**: 修改 `UnixDetector.__init__` 中的 `_DetectionPattern.query`（pgrep -f 正则）
- Pattern 匹配的是 CLI 命令行签名，需确保不会误匹配用户交互式终端

### 3. 调试检测不生效

按以下顺序排查：

1. 确认 CLI 进程确实在运行：
   - Windows: `Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -or $_.Name -eq 'claude.exe' } | Select-Object ProcessId, CommandLine`
   - Unix: `ps aux | grep -E 'claude|node.*cli.js'`
2. 确认 pattern 能匹配到命令行：对比实际 CommandLine 与 `_DetectionPattern.query`
3. 检查 `get_detector()` 返回的是否是期望的 Detector 类型
4. 开启 DEBUG 日志：`logging.getLogger("agent_worker.claude.process_detection").setLevel(logging.DEBUG)`

## 验证方式

### 快速验证（import 测试）

```bash
cd tools/claude-agent-worker
python -c "from agent_worker.claude.process_detection import get_detector; d = get_detector(); print(type(d).__name__); print('PIDs:', d.find_pids())"
```

### API 端点验证

Worker 启动后调用：

```bash
curl -s http://localhost:3031/api/v1/processes -H "Authorization: Bearer <token>" | python -m json.tool
```

预期：返回 `processes` 数组，每项含 `pid, command, memory_mb, started_at, is_orphan`。

### 生成测试脚本

当用户要求生成测试脚本时，按以下模板生成 pytest 文件：

```python
# tests/test_process_detection.py
"""Tests for the process detection strategy pattern."""
import os
from unittest.mock import patch, MagicMock

import pytest

from agent_worker.claude.process_detection import (
    ProcessInfo,
    WindowsDetector,
    UnixDetector,
    get_detector,
)


class TestWindowsDetector:
    """WindowsDetector tests — mock PowerShell subprocess calls."""

    @patch("agent_worker.claude.process_detection.subprocess.check_output")
    def test_find_pids_matches_node_exe(self, mock_out):
        mock_out.return_value = "1234\n5678\n"
        d = WindowsDetector()
        # Only first pattern call returns PIDs; second returns empty
        mock_out.side_effect = ["1234\n", ""]
        pids = d.find_pids()
        assert 1234 in pids
        assert mock_out.call_count == 2  # two patterns

    @patch("agent_worker.claude.process_detection.subprocess.check_output")
    def test_get_details_parses_tab_separated(self, mock_out):
        mock_out.return_value = (
            "1234\tnode.exe\t104857600\t03/04/2026 15:00:42\tnode.exe cli.js --output-format stream-json\n"
        )
        d = WindowsDetector()
        details = d.get_details({1234})
        assert len(details) == 1
        assert details[0].pid == 1234
        assert details[0].memory_mb == 100.0
        assert "2026" in details[0].started_at

    @patch("agent_worker.claude.process_detection.subprocess.check_output")
    def test_get_details_fallback_on_error(self, mock_out):
        mock_out.side_effect = FileNotFoundError("powershell not found")
        d = WindowsDetector()
        details = d.get_details({999})
        assert len(details) == 1
        assert details[0].pid == 999
        assert details[0].command == ""


class TestUnixDetector:
    """UnixDetector tests — mock pgrep and ps subprocess calls."""

    @patch("agent_worker.claude.process_detection.subprocess.check_output")
    def test_find_pids_via_pgrep(self, mock_out):
        mock_out.side_effect = ["2345\n", ""]  # pattern 1 hit, pattern 2 miss
        d = UnixDetector()
        pids = d.find_pids()
        assert 2345 in pids

    @patch("agent_worker.claude.process_detection.subprocess.check_output")
    def test_get_details_parses_ps_output(self, mock_out):
        mock_out.return_value = (
            "  2345 51200 Thu Mar  5 10:30:00 2026 /usr/bin/claude --output-format stream-json\n"
        )
        d = UnixDetector()
        details = d.get_details({2345})
        assert len(details) == 1
        assert details[0].pid == 2345
        assert details[0].memory_mb == 50.0


class TestGetDetector:
    """Factory function returns correct detector per platform."""

    @patch("agent_worker.claude.process_detection._detector", None)
    @patch("os.name", "nt")
    def test_returns_windows_on_nt(self):
        d = get_detector()
        assert isinstance(d, WindowsDetector)

    @patch("agent_worker.claude.process_detection._detector", None)
    @patch("os.name", "posix")
    def test_returns_unix_on_posix(self):
        d = get_detector()
        assert isinstance(d, UnixDetector)
```

运行测试：

```bash
cd tools/claude-agent-worker
python -m pytest tests/test_process_detection.py -v
```

## 约束条件

- **不改 CliProcessInfo model**：业务字段（is_orphan, foggy_task_id 等）属于 routes 层，不进入 ProcessInfo
- **不改 _enrich_processes**：富化逻辑独立于检测逻辑
- **Pattern 安全性**：检测 pattern 必须避免误匹配用户交互式 Claude 终端（靠 `--output-format stream-json` 或 `--print` 区分）
- **异常容忍**：所有 subprocess 调用失败时返回空结果，不抛异常
- **单例模式**：`get_detector()` 全局只创建一次，`_detector` 模块级变量

## 决策规则

- 如果新平台的进程检测命令与 Unix 相同（pgrep + ps）→ 继承 `UnixDetector` 只覆盖 patterns
- 如果新平台需要完全不同的检测机制 → 直接实现 `ProcessDetector` Protocol
- 如果检测 pattern 变更 → 先在目标平台手动验证命令行匹配，再改代码
- 如果用户报告检测不生效 → 按"调试检测不生效"流程排查
- 如果用户要求生成测试 → 按"生成测试脚本"模板输出 pytest 文件到 `tests/test_process_detection.py`
