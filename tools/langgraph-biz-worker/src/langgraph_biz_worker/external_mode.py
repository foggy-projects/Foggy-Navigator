"""Fail-closed external exposure state for the Biz Worker."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

EXTERNAL_AUTH_TOKEN_REQUIRED = "EXTERNAL_AUTH_TOKEN_REQUIRED"
EXTERNAL_EXECUTION_POLICY_PENDING = "EXTERNAL_EXECUTION_POLICY_PENDING"
EXTERNAL_WORKER_UNREADY = "EXTERNAL_WORKER_UNREADY"


@dataclass(frozen=True)
class ExternalModeState:
    """Non-sensitive external exposure state suitable for health responses."""

    mode: Literal["internal-dev", "external-enabled"]
    external_enabled: bool
    external_ready: bool
    auth_configured: bool
    reasons: tuple[str, ...]


def resolve_external_mode(external_enabled: bool, worker_token: str) -> ExternalModeState:
    """Resolve the external gate without treating a token as production readiness.

    The full execution-policy boundary (workspace, tools, sandbox, approval and
    network limits) is intentionally still pending, so explicitly enabling the
    external mode keeps business ingress closed even when a token is present.
    """
    auth_configured = bool(worker_token.strip())
    reasons: list[str] = []
    if external_enabled:
        if not auth_configured:
            reasons.append(EXTERNAL_AUTH_TOKEN_REQUIRED)
        reasons.append(EXTERNAL_EXECUTION_POLICY_PENDING)
    return ExternalModeState(
        mode="external-enabled" if external_enabled else "internal-dev",
        external_enabled=external_enabled,
        external_ready=external_enabled and not reasons,
        auth_configured=auth_configured,
        reasons=tuple(reasons),
    )
