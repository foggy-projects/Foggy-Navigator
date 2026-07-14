"""Environment boundary for model- or integration-controlled subprocesses."""

from __future__ import annotations

import os
from collections.abc import Mapping


_SAFE_SUBPROCESS_ENV_KEYS = frozenset({
    # Process identity and conventional filesystem locations.
    "HOME",
    "LOGNAME",
    "PATH",
    "SHELL",
    "TEMP",
    "TERM",
    "TMP",
    "TMPDIR",
    "TZ",
    "USER",
    # Locale values are needed by compilers and package managers, and do not
    # grant access to a Worker-side capability.
    "LANG",
    "LANGUAGE",
    "LC_ADDRESS",
    "LC_ALL",
    "LC_COLLATE",
    "LC_CTYPE",
    "LC_IDENTIFICATION",
    "LC_MEASUREMENT",
    "LC_MESSAGES",
    "LC_MONETARY",
    "LC_NAME",
    "LC_NUMERIC",
    "LC_PAPER",
    "LC_TELEPHONE",
    "LC_TIME",
    # Installation roots required to locate common development toolchains.
    # Flags and package-registry settings are deliberately excluded because
    # they may contain credentials or inject executable code.
    "CARGO_HOME",
    "CONDA_PREFIX",
    "DOTNET_ROOT",
    "GOPATH",
    "GOROOT",
    "GRADLE_HOME",
    "JAVA_HOME",
    "JDK_HOME",
    "M2_HOME",
    "MAVEN_HOME",
    "NODE_HOME",
    "NVM_BIN",
    "NVM_DIR",
    "PNPM_HOME",
    "PYENV_ROOT",
    "RUSTUP_HOME",
    "VIRTUAL_ENV",
    "VOLTA_HOME",
    # Corporate CA locations are paths, not bearer credentials.
    "CURL_CA_BUNDLE",
    "NODE_EXTRA_CA_CERTS",
    "REQUESTS_CA_BUNDLE",
    "SSL_CERT_DIR",
    "SSL_CERT_FILE",
})


def sanitized_worker_subprocess_env(
    base_env: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """Build a minimal environment for a model-controlled subprocess.

    An allowlist is intentional here: Provider keys, webhook/Skill secrets,
    cloud credentials, database URLs and any future Worker credentials must
    stay in the Worker process even when their variable names are not yet
    known to this module.
    """

    source = os.environ if base_env is None else base_env
    return {
        key: value
        for key, value in source.items()
        if key.upper() in _SAFE_SUBPROCESS_ENV_KEYS
    }
