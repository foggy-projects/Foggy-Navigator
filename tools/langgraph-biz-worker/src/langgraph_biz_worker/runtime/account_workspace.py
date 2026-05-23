"""Account workspace resolution for managed and delegated workspace modes."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .execution_policy import ExecutionPolicy


DELEGATED_STORAGE_ACCOUNT_ID = "delegated"


@dataclass(frozen=True)
class AccountWorkspace:
    """Resolved physical workspace for account-scoped runtime files."""

    account_id: str | None
    root: Path
    mode: str
    source: str
    trusted: bool = True

    @property
    def storage_account_id(self) -> str:
        """Stable account id used by APIs that still require a non-empty id."""

        return self.account_id or DELEGATED_STORAGE_ACCOUNT_ID

    @property
    def skills_root(self) -> Path:
        return self.root / "skills"

    @property
    def artifacts_root(self) -> Path:
        return self.root / "artifacts"


class AccountWorkspaceResolver:
    """Resolve account ids to physical workspace roots.

    First-phase priority:
    1. delegated ``ExecutionPolicy.workdir`` supplied by an authenticated upstream;
    2. managed ``<data_root>/accounts/<account_id>``.
    """

    def __init__(self, data_root: Path | str) -> None:
        self._data_root = Path(data_root).resolve()
        self._managed_root = self._data_root / "accounts"

    def resolve(
        self,
        account_id: str | None,
        *,
        execution_policy: ExecutionPolicy | None = None,
    ) -> AccountWorkspace | None:
        if execution_policy and execution_policy.configured and execution_policy.workdir:
            root = execution_policy.workdir.resolve()
            _reject_symlink_root(root, "delegated workspace root")
            if account_id:
                _validate_account_id(account_id)
            return AccountWorkspace(
                account_id=account_id,
                root=root,
                mode="delegated",
                source="execution_policy",
            )

        if not account_id:
            return None

        _validate_account_id(account_id)
        root = (self._managed_root / account_id).resolve()
        try:
            root.relative_to(self._managed_root.resolve())
        except ValueError as exc:
            raise ValueError("account workspace escapes managed accounts root") from exc
        _reject_symlink_root(root, "account workspace root")
        return AccountWorkspace(
            account_id=account_id,
            root=root,
            mode="managed",
            source="default",
        )


def resolve_account_workspace(
    data_root: Path | str,
    account_id: str | None,
    *,
    execution_policy: ExecutionPolicy | None = None,
) -> AccountWorkspace | None:
    return AccountWorkspaceResolver(data_root).resolve(
        account_id,
        execution_policy=execution_policy,
    )


def _validate_account_id(account_id: str) -> None:
    """Reject path traversal in account IDs."""

    if not account_id or account_id.strip() != account_id:
        raise ValueError("account_id must be non-empty and trimmed")
    if account_id in {".", ".."} or "/" in account_id or "\\" in account_id:
        raise ValueError("account_id must be a single path segment")


def _reject_symlink_root(root: Path, label: str) -> None:
    if root.exists() and root.is_symlink():
        raise ValueError(f"{label} must not be a symlink")
