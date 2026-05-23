from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SAMPLE_ROOT = PROJECT_ROOT / "samples" / "standalone-project"


def test_standalone_project_embedded_sample_runs():
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join([
        str(PROJECT_ROOT / "src"),
        str(SAMPLE_ROOT),
        env.get("PYTHONPATH", ""),
    ])
    runtime_dir = SAMPLE_ROOT / ".runtime"
    try:
        result = subprocess.run(
            [sys.executable, "run_embedded.py"],
            cwd=SAMPLE_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=True,
        )
    finally:
        if runtime_dir.exists():
            shutil.rmtree(runtime_dir)

    assert "Order O-1001 is OPEN." in result.stdout
    assert "'status': 'OPEN'" in result.stdout


def test_standalone_project_service_smoke_script_compiles():
    import py_compile

    py_compile.compile(str(SAMPLE_ROOT / "service_smoke.py"), doraise=True)
