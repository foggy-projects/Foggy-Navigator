"""Tests for AccountFileTools — list/read/write/str_replace/edit/patch."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.account_file_tools import AccountFileTools, FileToolError


def _setup_skill(tmp_path: Path, account: str = "user-001", skill: str = "my-skill") -> AccountFileTools:
    """Create minimal account + skill directory and return configured tools."""
    skill_dir = tmp_path / "accounts" / account / "skills" / skill
    skill_dir.mkdir(parents=True)
    (skill_dir / "references").mkdir()
    (skill_dir / "assets").mkdir()
    return AccountFileTools(tmp_path, account, task_id="task-001")


def _sha256(data: str) -> str:
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# list_files
# ---------------------------------------------------------------------------

class TestListFiles:
    def test_list_skills_root(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.list_files("skills")
        assert result["ok"]
        paths = [e["path"] for e in result["entries"]]
        assert any("my-skill" in p for p in paths)

    def test_list_no_real_paths(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.list_files("skills/my-skill")
        for entry in result["entries"]:
            # Entry paths should be relative (no drive letters, no absolute paths)
            assert not entry["path"].startswith("/")
            assert ":" not in entry["path"]

    def test_list_empty_dir(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.list_files("skills/my-skill/references")
        assert result["ok"]
        assert result["entries"] == []


# ---------------------------------------------------------------------------
# read_file
# ---------------------------------------------------------------------------

class TestReadFile:
    def test_read_existing(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        skill_md = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        skill_md.write_text("---\nname: my-skill\n---\n# My Skill\n", encoding="utf-8")

        result = tools.read_file("skills/my-skill/SKILL.md")
        assert result["ok"]
        assert "my-skill" in result["content"]
        assert not result["truncated"]

    def test_read_default_max_lines(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        ref = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "references" / "big.md"
        ref.write_text("\n".join(f"line {i}" for i in range(500)), encoding="utf-8")

        result = tools.read_file("skills/my-skill/references/big.md")
        assert result["truncated"]
        assert result["end_line"] <= 200

    def test_read_nonexistent(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        with pytest.raises(FileToolError) as exc_info:
            tools.read_file("skills/my-skill/SKILL.md")
        assert exc_info.value.code == "file_not_found"


# ---------------------------------------------------------------------------
# write_file
# ---------------------------------------------------------------------------

class TestWriteFile:
    def test_create_skill_md(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.write_file(
            "skills/my-skill/SKILL.md",
            content="---\nname: my-skill\n---\n# Test\n",
            mode="create",
        )
        assert result["ok"]
        assert result["relative_path"] == "skills/my-skill/SKILL.md"
        # Verify on disk
        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert p.read_text(encoding="utf-8").startswith("---")

    def test_create_rejects_existing(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="first", mode="create")
        with pytest.raises(FileToolError) as exc_info:
            tools.write_file("skills/my-skill/SKILL.md", content="second", mode="create")
        assert exc_info.value.code == "file_exists"

    def test_overwrite_explicit(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="v1", mode="create")
        result = tools.write_file("skills/my-skill/SKILL.md", content="v2", mode="overwrite")
        assert result["ok"]
        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert p.read_text(encoding="utf-8") == "v2"

    def test_overwrite_not_default(self, tmp_path: Path):
        """Default mode is 'create' — should not silently overwrite."""
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="v1")
        with pytest.raises(FileToolError):
            tools.write_file("skills/my-skill/SKILL.md", content="v2")  # default mode=create

    def test_write_too_large(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        big = "x" * (1024 * 1024 + 1)
        with pytest.raises(FileToolError) as exc_info:
            tools.write_file("skills/my-skill/SKILL.md", content=big)
        assert exc_info.value.code == "file_too_large"

    def test_write_audit_record(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="content")
        assert len(tools.audit_records) == 1
        rec = tools.audit_records[0]
        assert rec["operation"] == "write_file"
        assert rec["account_id"] == "user-001"
        assert rec["task_id"] == "task-001"
        assert rec["relative_path"] == "skills/my-skill/SKILL.md"
        assert rec["sha256_after"] is not None
        assert rec["timestamp"]
        assert rec["actor"] == "llm"


# ---------------------------------------------------------------------------
# expected_sha256 (optimistic concurrency)
# ---------------------------------------------------------------------------

class TestExpectedSha256:
    def test_overwrite_correct_sha256(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="v1")
        sha = _sha256("v1")
        result = tools.write_file(
            "skills/my-skill/SKILL.md", content="v2",
            mode="overwrite", expected_sha256=sha,
        )
        assert result["ok"]

    def test_overwrite_wrong_sha256(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="v1")
        with pytest.raises(FileToolError) as exc_info:
            tools.write_file(
                "skills/my-skill/SKILL.md", content="v2",
                mode="overwrite", expected_sha256="bad_hash",
            )
        assert exc_info.value.code == "checksum_mismatch"

    def test_patch_correct_sha256(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        original = "line1\nline2\nline3\n"
        tools.write_file("skills/my-skill/SKILL.md", content=original)
        sha = _sha256(original)

        patch = (
            "--- a/x\n+++ b/x\n"
            "@@ -1,3 +1,3 @@\n"
            " line1\n-line2\n+line2_modified\n line3\n"
        )
        result = tools.patch_file("skills/my-skill/SKILL.md", patch=patch, expected_sha256=sha)
        assert result["ok"]

    def test_patch_wrong_sha256(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="line1\nline2\nline3\n")
        patch = (
            "--- a/x\n+++ b/x\n"
            "@@ -1,3 +1,3 @@\n"
            " line1\n-line2\n+line2_modified\n line3\n"
        )
        with pytest.raises(FileToolError) as exc_info:
            tools.patch_file("skills/my-skill/SKILL.md", patch=patch, expected_sha256="bad")
        assert exc_info.value.code == "checksum_mismatch"


# ---------------------------------------------------------------------------
# str_replace
# ---------------------------------------------------------------------------

class TestStrReplace:
    def test_unique_match(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="hello world")
        result = tools.str_replace("skills/my-skill/SKILL.md", old_str="hello", new_str="goodbye")
        assert result["ok"]
        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert p.read_text(encoding="utf-8") == "goodbye world"

    def test_zero_match_rejected(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="hello")
        with pytest.raises(FileToolError) as exc_info:
            tools.str_replace("skills/my-skill/SKILL.md", old_str="nonexistent", new_str="x")
        assert exc_info.value.code == "no_match"

    def test_multiple_matches_rejected(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="aa bb aa")
        with pytest.raises(FileToolError) as exc_info:
            tools.str_replace("skills/my-skill/SKILL.md", old_str="aa", new_str="cc")
        assert exc_info.value.code == "multiple_matches"


# ---------------------------------------------------------------------------
# edit_file
# ---------------------------------------------------------------------------

class TestEditFile:
    def test_replace_section(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        content = "# Intro\nOld intro.\n## Details\nOld details.\n## Summary\nEnd.\n"
        tools.write_file("skills/my-skill/SKILL.md", content=content)

        result = tools.edit_file(
            "skills/my-skill/SKILL.md",
            operation="replace_section",
            anchor="## Details",
            content="New details.\n",
        )
        assert result["ok"]

        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        new_text = p.read_text(encoding="utf-8")
        assert "New details." in new_text
        assert "Old details." not in new_text
        assert "## Summary" in new_text  # next section preserved

    def test_anchor_not_found(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        tools.write_file("skills/my-skill/SKILL.md", content="# Title\nBody\n")
        with pytest.raises(FileToolError) as exc_info:
            tools.edit_file("skills/my-skill/SKILL.md", "replace_section", "## Nonexistent", "x")
        assert exc_info.value.code == "anchor_not_found"


# ---------------------------------------------------------------------------
# patch_file
# ---------------------------------------------------------------------------

class TestPatchFile:
    def test_valid_unified_diff(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        original = "alpha\nbeta\ngamma\n"
        tools.write_file("skills/my-skill/SKILL.md", content=original)

        patch = (
            "--- a/file\n+++ b/file\n"
            "@@ -1,3 +1,3 @@\n"
            " alpha\n-beta\n+BETA\n gamma\n"
        )
        result = tools.patch_file("skills/my-skill/SKILL.md", patch=patch)
        assert result["ok"]
        assert result["changed"]

        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert "BETA" in p.read_text(encoding="utf-8")

    def test_conflict_full_reject(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        original = "alpha\nbeta\ngamma\n"
        tools.write_file("skills/my-skill/SKILL.md", content=original)

        # Patch references wrong content
        patch = (
            "--- a/file\n+++ b/file\n"
            "@@ -1,3 +1,3 @@\n"
            " alpha\n-WRONG\n+REPLACED\n gamma\n"
        )
        with pytest.raises(FileToolError) as exc_info:
            tools.patch_file("skills/my-skill/SKILL.md", patch=patch)
        assert exc_info.value.code == "patch_conflict"

        # File unchanged (no partial write)
        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert p.read_text(encoding="utf-8") == original

    def test_patch_nonexistent_file(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        patch = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n"
        with pytest.raises(FileToolError) as exc_info:
            tools.patch_file("skills/my-skill/SKILL.md", patch=patch)
        assert exc_info.value.code == "file_not_found"

    def test_multi_file_patch_rejected(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        original = "alpha\nbeta\ngamma\n"
        tools.write_file("skills/my-skill/SKILL.md", content=original)

        patch = (
            "--- a/file1\n+++ b/file1\n"
            "@@ -1,3 +1,3 @@\n"
            " alpha\n-beta\n+BETA\n gamma\n"
            "--- a/file2\n+++ b/file2\n"
            "@@ -1,1 +1,1 @@\n"
            "-other\n+OTHER\n"
        )
        with pytest.raises(FileToolError) as exc_info:
            tools.patch_file("skills/my-skill/SKILL.md", patch=patch)
        assert exc_info.value.code == "patch_conflict"

        p = tmp_path / "accounts" / "user-001" / "skills" / "my-skill" / "SKILL.md"
        assert p.read_text(encoding="utf-8") == original


# ---------------------------------------------------------------------------
# Permission and path rejection (via file tools)
# ---------------------------------------------------------------------------

class TestPathRejection:
    def test_absolute_path_rejected(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        with pytest.raises(FileToolError):
            tools.read_file("/etc/passwd")

    def test_traversal_rejected(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        with pytest.raises(FileToolError):
            tools.read_file("skills/../../../etc/passwd")

    def test_other_account_rejected(self, tmp_path: Path):
        """Writing to another account's directory should fail at the path guard level."""
        # Create account user-001 tools
        tools = _setup_skill(tmp_path, account="user-001")
        # The path guard only resolves under user-001, so "skills/..." is safe
        # but there's no way to reach user-002 via relative path
        with pytest.raises(FileToolError):
            tools.write_file("../user-002/skills/evil/SKILL.md", content="hack")


# ---------------------------------------------------------------------------
# .fsscript via file tools
# ---------------------------------------------------------------------------

class TestFsscriptFileTools:
    def test_write_fsscript_in_references(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.write_file(
            "skills/my-skill/references/example.fsscript",
            content="some script text",
        )
        assert result["ok"]

    def test_write_fsscript_in_assets(self, tmp_path: Path):
        tools = _setup_skill(tmp_path)
        result = tools.write_file(
            "skills/my-skill/assets/tool.fsscript",
            content="tool content",
        )
        assert result["ok"]


# ---------------------------------------------------------------------------
# Symlink rejection via file tools
# ---------------------------------------------------------------------------

@pytest.mark.skipif(sys.platform == "win32", reason="symlink tests require Linux")
class TestSymlinkFileTools:
    def test_write_to_symlink_target(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001" / "skills" / "evil"
        acct.mkdir(parents=True)
        target = tmp_path / "outside.md"
        target.write_text("original", encoding="utf-8")
        (acct / "SKILL.md").symlink_to(target)

        tools = AccountFileTools(tmp_path, "user-001", "t1")
        with pytest.raises(FileToolError):
            tools.write_file("skills/evil/SKILL.md", content="pwned", mode="overwrite")

    def test_read_symlink_target(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001" / "skills" / "evil"
        acct.mkdir(parents=True)
        target = tmp_path / "secret.md"
        target.write_text("secret", encoding="utf-8")
        (acct / "SKILL.md").symlink_to(target)

        tools = AccountFileTools(tmp_path, "user-001", "t1")
        with pytest.raises(FileToolError):
            tools.read_file("skills/evil/SKILL.md")
