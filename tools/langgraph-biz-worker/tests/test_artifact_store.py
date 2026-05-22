"""Tests for ArtifactStore — create/read/isolate artifacts."""

from __future__ import annotations

from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.artifact_store import ArtifactError, ArtifactStore
from langgraph_biz_worker.runtime.account_workspace import DELEGATED_STORAGE_ACCOUNT_ID, resolve_account_workspace
from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy


@pytest.fixture
def store(tmp_path: Path) -> ArtifactStore:
    return ArtifactStore(tmp_path)


# ---------------------------------------------------------------------------
# Create
# ---------------------------------------------------------------------------

class TestCreateArtifact:
    def test_create_task_scope(self, store: ArtifactStore):
        result = store.create(
            account_id="acct-001",
            task_id="task-001",
            scope="task",
            name="test-payload",
            content='{"data": "value"}',
            mime_type="application/json",
            summary="test payload",
        )
        assert result["artifact_id"].startswith("art_")
        assert result["name"] == "test-payload"
        assert result["scope"] == "task"
        assert result["mime_type"] == "application/json"
        assert result["size"] == len('{"data": "value"}'.encode("utf-8"))
        assert result["summary"] == "test payload"
        assert "content_ref" not in result

    def test_create_account_scope(self, store: ArtifactStore):
        result = store.create(
            account_id="acct-001",
            task_id=None,
            scope="account",
            name="shared-doc",
            content="long text here",
            summary="a shared document",
        )
        assert result["scope"] == "account"
        assert result["artifact_id"].startswith("art_")
        assert "content_ref" not in result

    def test_create_auto_summary(self, store: ArtifactStore):
        """When summary is empty, an auto-summary should be generated."""
        result = store.create(
            account_id="acct-001",
            task_id="t1",
            scope="task",
            name="auto",
            content="data",
        )
        assert "auto" in result["summary"].lower() or "artifact" in result["summary"].lower()


# ---------------------------------------------------------------------------
# content_ref never leaked
# ---------------------------------------------------------------------------

class TestContentRefHidden:
    def test_create_no_content_ref(self, store: ArtifactStore):
        result = store.create(
            account_id="acct-001", task_id="t1", scope="task",
            name="x", content="y",
        )
        assert "content_ref" not in result

    def test_read_summary_no_content_ref(self, store: ArtifactStore):
        create = store.create(
            account_id="acct-001", task_id="t1", scope="task",
            name="x", content="y",
        )
        read = store.read(
            account_id="acct-001", task_id="t1",
            artifact_id=create["artifact_id"], mode="summary",
        )
        assert "content_ref" not in read

    def test_read_metadata_no_content_ref(self, store: ArtifactStore):
        create = store.create(
            account_id="acct-001", task_id="t1", scope="task",
            name="x", content="y",
        )
        read = store.read(
            account_id="acct-001", task_id="t1",
            artifact_id=create["artifact_id"], mode="metadata",
        )
        assert "content_ref" not in read

    def test_read_content_no_content_ref(self, store: ArtifactStore):
        create = store.create(
            account_id="acct-001", task_id="t1", scope="task",
            name="x", content="payload",
        )
        read = store.read(
            account_id="acct-001", task_id="t1",
            artifact_id=create["artifact_id"], mode="content",
        )
        assert "content_ref" not in read
        assert read["content"] == "payload"


# ---------------------------------------------------------------------------
# Read modes
# ---------------------------------------------------------------------------

class TestReadModes:
    def test_default_summary(self, store: ArtifactStore):
        create = store.create(
            account_id="a1", task_id="t1", scope="task",
            name="n", content="data", summary="my summary",
        )
        read = store.read(account_id="a1", task_id="t1", artifact_id=create["artifact_id"])
        assert read["summary"] == "my summary"
        assert "content" not in read

    def test_mode_content(self, store: ArtifactStore):
        create = store.create(
            account_id="a1", task_id="t1", scope="task",
            name="n", content="hello world",
        )
        read = store.read(
            account_id="a1", task_id="t1",
            artifact_id=create["artifact_id"], mode="content",
        )
        assert read["content"] == "hello world"

    def test_mode_metadata(self, store: ArtifactStore):
        create = store.create(
            account_id="a1", task_id="t1", scope="task",
            name="n", content="x",
        )
        read = store.read(
            account_id="a1", task_id="t1",
            artifact_id=create["artifact_id"], mode="metadata",
        )
        assert "sha256" in read
        assert "created_at" in read
        assert "content" not in read

    def test_delegated_workspace_artifacts_without_account_id(self, tmp_path: Path):
        data_root = tmp_path / "data"
        workspace_root = tmp_path / "workspace"
        workspace_root.mkdir()
        policy = ExecutionPolicy.from_context({
            "execution_policy": {
                "workdir": str(workspace_root),
                "allowed_dirs": [str(workspace_root)],
            },
        })
        workspace = resolve_account_workspace(data_root, None, execution_policy=policy)
        store = ArtifactStore(data_root, execution_policy=policy, workspace=workspace)

        created = store.create(
            account_id=DELEGATED_STORAGE_ACCOUNT_ID,
            task_id="task-001",
            scope="task",
            name="delegated-doc",
            content="payload",
            summary="delegated summary",
        )
        read = store.read(
            account_id=DELEGATED_STORAGE_ACCOUNT_ID,
            task_id="task-001",
            artifact_id=created["artifact_id"],
            mode="content",
        )

        assert read["content"] == "payload"
        assert (workspace_root / "artifacts").is_dir()
        assert not (data_root / "accounts" / DELEGATED_STORAGE_ACCOUNT_ID / "artifacts").exists()


# ---------------------------------------------------------------------------
# Path boundary validation
# ---------------------------------------------------------------------------

class TestPathBoundaryValidation:
    @pytest.mark.parametrize("account_id", ["../evil", "acct/evil", " acct"])
    def test_create_rejects_invalid_account_id(self, store: ArtifactStore, account_id: str):
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id=account_id,
                task_id="t1",
                scope="task",
                name="bad",
                content="x",
            )
        assert exc_info.value.code == "invalid_account_id"

    @pytest.mark.parametrize("task_id", ["../evil", "task/evil", " task"])
    def test_create_rejects_invalid_task_id(self, store: ArtifactStore, task_id: str):
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id="acct-001",
                task_id=task_id,
                scope="task",
                name="bad",
                content="x",
            )
        assert exc_info.value.code == "invalid_task_id"

    def test_read_rejects_invalid_artifact_id(self, store: ArtifactStore):
        with pytest.raises(ArtifactError) as exc_info:
            store.read(account_id="acct-001", task_id="t1", artifact_id="../secret")
        assert exc_info.value.code == "invalid_artifact_id"

    def test_tampered_content_ref_outside_account_is_rejected(self, tmp_path: Path):
        store = ArtifactStore(tmp_path)
        create = store.create(
            account_id="acct-001",
            task_id="t1",
            scope="task",
            name="safe",
            content="safe content",
        )

        meta_path = next((tmp_path / "accounts" / "acct-001" / "artifacts").rglob(
            f"{create['artifact_id']}.json"
        ))
        metadata = meta_path.read_text(encoding="utf-8")
        metadata = metadata.replace(
            "accounts/acct-001/artifacts/task/t1/content/",
            "../",
        )
        meta_path.write_text(metadata, encoding="utf-8")

        with pytest.raises(ArtifactError) as exc_info:
            store.read(
                account_id="acct-001",
                task_id="t1",
                artifact_id=create["artifact_id"],
                mode="content",
            )
        assert exc_info.value.code == "access_denied"


# ---------------------------------------------------------------------------
# Isolation
# ---------------------------------------------------------------------------

class TestIsolation:
    def test_cross_account_read_rejected(self, store: ArtifactStore):
        create = store.create(
            account_id="acct-001", task_id="t1", scope="task",
            name="secret", content="classified",
        )
        with pytest.raises(ArtifactError) as exc_info:
            store.read(
                account_id="acct-002", task_id="t1",
                artifact_id=create["artifact_id"],
            )
        assert exc_info.value.code == "artifact_not_found"  # different account dir

    def test_task_scope_cross_task_read_rejected(self, store: ArtifactStore):
        create = store.create(
            account_id="acct-001", task_id="task-A", scope="task",
            name="task-secret", content="data",
        )
        with pytest.raises(ArtifactError) as exc_info:
            store.read(
                account_id="acct-001", task_id="task-B",
                artifact_id=create["artifact_id"],
            )
        assert exc_info.value.code == "access_denied"

    def test_account_scope_no_task_check(self, store: ArtifactStore):
        """Account-scope artifacts can be read with any task_id."""
        create = store.create(
            account_id="acct-001", task_id=None, scope="account",
            name="shared", content="shared data",
        )
        read = store.read(
            account_id="acct-001", task_id="any-task",
            artifact_id=create["artifact_id"],
        )
        assert read["name"] == "shared"


# ---------------------------------------------------------------------------
# Validation errors
# ---------------------------------------------------------------------------

class TestValidationErrors:
    def test_invalid_scope(self, store: ArtifactStore):
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id="a1", task_id="t1", scope="workspace",
                name="bad", content="x",
            )
        assert exc_info.value.code == "invalid_scope"

    def test_missing_task_context(self, store: ArtifactStore):
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id="a1", task_id=None, scope="task",
                name="bad", content="x",
            )
        assert exc_info.value.code == "missing_task_context"

    def test_missing_account(self, store: ArtifactStore):
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id="", task_id="t1", scope="task",
                name="bad", content="x",
            )
        assert exc_info.value.code == "account_context_required"

    def test_content_too_large(self, store: ArtifactStore):
        big = "x" * (1024 * 1024 + 1)
        with pytest.raises(ArtifactError) as exc_info:
            store.create(
                account_id="a1", task_id="t1", scope="task",
                name="big", content=big,
            )
        assert exc_info.value.code == "content_too_large"

    def test_artifact_not_found(self, store: ArtifactStore):
        with pytest.raises(ArtifactError) as exc_info:
            store.read(account_id="a1", task_id="t1", artifact_id="art_nonexistent")
        assert exc_info.value.code == "artifact_not_found"
