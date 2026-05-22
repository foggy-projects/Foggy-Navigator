"""Tests for AccountPathGuard — centralised path permission enforcement."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.account_path_guard import (
    ERR_FILE_TYPE,
    ERR_FORBIDDEN,
    ERR_INVALID_PATH,
    ERR_SYMLINK,
    ERR_TRAVERSAL,
    AccountPathGuard,
    PathGuardError,
)


@pytest.fixture
def guard(tmp_path: Path) -> AccountPathGuard:
    """AccountPathGuard with a temp data_root and account 'user-001'."""
    # Pre-create account root so resolve checks succeed
    account_root = tmp_path / "accounts" / "user-001"
    account_root.mkdir(parents=True)
    return AccountPathGuard(tmp_path, "user-001")


@pytest.fixture
def populated_guard(tmp_path: Path) -> AccountPathGuard:
    """Guard with pre-populated skill files on disk."""
    acct = tmp_path / "accounts" / "user-001"
    skill = acct / "skills" / "my-skill"
    (skill / "references").mkdir(parents=True)
    (skill / "assets").mkdir(parents=True)
    (skill / "SKILL.md").write_text("---\nname: my-skill\n---\n# My Skill\n", encoding="utf-8")
    (skill / "references" / "doc.md").write_text("# Doc\n", encoding="utf-8")
    (skill / "assets" / "data.json").write_text("{}", encoding="utf-8")
    return AccountPathGuard(tmp_path, "user-001")


# ---------------------------------------------------------------------------
# Valid paths
# ---------------------------------------------------------------------------

class TestValidPaths:
    def test_skill_md(self, populated_guard: AccountPathGuard):
        p = populated_guard.resolve_read("skills/my-skill/SKILL.md")
        assert p.name == "SKILL.md"

    def test_references_md(self, populated_guard: AccountPathGuard):
        p = populated_guard.resolve_read("skills/my-skill/references/doc.md")
        assert p.name == "doc.md"

    def test_assets_json(self, populated_guard: AccountPathGuard):
        p = populated_guard.resolve_read("skills/my-skill/assets/data.json")
        assert p.name == "data.json"

    def test_write_skill_md(self, populated_guard: AccountPathGuard):
        p = populated_guard.resolve_write("skills/my-skill/SKILL.md")
        assert p.name == "SKILL.md"


# ---------------------------------------------------------------------------
# Reject absolute paths
# ---------------------------------------------------------------------------

class TestRejectAbsolute:
    def test_unix_absolute(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("/etc/passwd")
        assert exc_info.value.code == ERR_TRAVERSAL

    def test_windows_absolute(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("C:\\Windows\\system32")
        assert exc_info.value.code == ERR_TRAVERSAL


# ---------------------------------------------------------------------------
# Reject traversal
# ---------------------------------------------------------------------------

class TestRejectTraversal:
    def test_dot_dot(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/../../../etc/passwd")
        assert exc_info.value.code == ERR_TRAVERSAL

    def test_single_dot(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/./my-skill/SKILL.md")
        assert exc_info.value.code == ERR_TRAVERSAL

    def test_empty_segment(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills//my-skill/SKILL.md")
        assert exc_info.value.code == ERR_INVALID_PATH

    def test_empty_path(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("")
        assert exc_info.value.code == ERR_INVALID_PATH

    def test_dot_dot_as_skill_name(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/../SKILL.md")
        assert exc_info.value.code == ERR_TRAVERSAL


# ---------------------------------------------------------------------------
# Reject forbidden targets
# ---------------------------------------------------------------------------

class TestRejectForbidden:
    def test_public_skill(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/public/SKILL.md")
        assert exc_info.value.code == ERR_FORBIDDEN

    def test_builtin_skill(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/builtin/SKILL.md")
        assert exc_info.value.code == ERR_FORBIDDEN

    def test_non_skill_path(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("config/settings.json")
        assert exc_info.value.code == ERR_FORBIDDEN

    def test_skill_root_arbitrary_file(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/my-skill/run.sh")
        assert exc_info.value.code == ERR_FORBIDDEN

    def test_references_dir_only(self, guard: AccountPathGuard):
        """references/ without a file target should be rejected for read/write."""
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/my-skill/references")
        assert exc_info.value.code == ERR_FORBIDDEN


# ---------------------------------------------------------------------------
# File type enforcement
# ---------------------------------------------------------------------------

class TestFileTypeEnforcement:
    @pytest.mark.parametrize("ext", [".md", ".txt", ".json", ".yaml", ".yml", ".fsscript"])
    def test_allowed_extensions(self, guard: AccountPathGuard, ext: str):
        """All listed extensions should pass validation (even if file doesn't exist yet)."""
        # resolve_write checks path rules but doesn't require file existence
        # for the validation step (it creates the path)
        path = f"skills/my-skill/references/file{ext}"
        # Should not raise PathGuardError for the path pattern
        try:
            guard.resolve_write(path)
        except PathGuardError:
            pytest.fail(f"Extension {ext} should be allowed")

    def test_reject_py_extension(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/my-skill/references/script.py")
        assert exc_info.value.code == ERR_FILE_TYPE

    def test_reject_sh_extension(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/my-skill/assets/run.sh")
        assert exc_info.value.code == ERR_FILE_TYPE

    def test_reject_exe(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/my-skill/assets/malware.exe")
        assert exc_info.value.code == ERR_FILE_TYPE


# ---------------------------------------------------------------------------
# .fsscript allowed in references/assets, not as SKILL.md replacement
# ---------------------------------------------------------------------------

class TestFsscript:
    def test_fsscript_in_references(self, guard: AccountPathGuard):
        """Should be allowed."""
        try:
            guard.resolve_write("skills/my-skill/references/example.fsscript")
        except PathGuardError:
            pytest.fail(".fsscript should be allowed in references/")

    def test_fsscript_in_assets(self, guard: AccountPathGuard):
        try:
            guard.resolve_write("skills/my-skill/assets/example.fsscript")
        except PathGuardError:
            pytest.fail(".fsscript should be allowed in assets/")

    def test_fsscript_cannot_replace_skill_md(self, guard: AccountPathGuard):
        """Cannot put a .fsscript at the skill root level."""
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/my-skill/example.fsscript")
        assert exc_info.value.code == ERR_FORBIDDEN


# ---------------------------------------------------------------------------
# list_files paths
# ---------------------------------------------------------------------------

class TestListPaths:
    def test_list_skills_root(self, guard: AccountPathGuard):
        guard.resolve_list("skills")  # Should not raise

    def test_list_skill_dir(self, guard: AccountPathGuard):
        guard.resolve_list("skills/my-skill")

    def test_list_references(self, guard: AccountPathGuard):
        guard.resolve_list("skills/my-skill/references")

    def test_list_assets(self, guard: AccountPathGuard):
        guard.resolve_list("skills/my-skill/assets")

    def test_list_deep_path_rejected(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_list("skills/my-skill/references/deep")
        assert exc_info.value.code == ERR_FORBIDDEN

    def test_list_non_skills_rejected(self, guard: AccountPathGuard):
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_list("config")
        assert exc_info.value.code == ERR_FORBIDDEN


# ---------------------------------------------------------------------------
# Symlink rejection (Linux only)
# ---------------------------------------------------------------------------

@pytest.mark.skipif(sys.platform == "win32", reason="symlink tests require Linux")
class TestSymlinkRejection:
    def test_symlink_target(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001" / "skills" / "evil"
        acct.mkdir(parents=True)
        target = tmp_path / "outside.md"
        target.write_text("secret", encoding="utf-8")
        link = acct / "SKILL.md"
        link.symlink_to(target)

        guard = AccountPathGuard(tmp_path, "user-001")
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/evil/SKILL.md")
        assert exc_info.value.code == ERR_SYMLINK

    def test_symlink_parent_dir(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001" / "skills"
        acct.mkdir(parents=True)
        real_dir = tmp_path / "real-skill"
        real_dir.mkdir()
        (real_dir / "SKILL.md").write_text("content", encoding="utf-8")
        link = acct / "linked-skill"
        link.symlink_to(real_dir)

        guard = AccountPathGuard(tmp_path, "user-001")
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/linked-skill/SKILL.md")
        assert exc_info.value.code == ERR_SYMLINK

    def test_symlink_parent_dir_inside_account(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001"
        skills = acct / "skills"
        skills.mkdir(parents=True)
        real_dir = acct / "real-skill"
        real_dir.mkdir()
        (real_dir / "SKILL.md").write_text("content", encoding="utf-8")
        link = skills / "linked-skill"
        link.symlink_to(real_dir)

        guard = AccountPathGuard(tmp_path, "user-001")
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_read("skills/linked-skill/SKILL.md")
        assert exc_info.value.code == ERR_SYMLINK

    def test_symlink_in_write_path(self, tmp_path: Path):
        acct = tmp_path / "accounts" / "user-001" / "skills"
        acct.mkdir(parents=True)
        real_dir = tmp_path / "outside-target"
        real_dir.mkdir()
        link = acct / "evil-skill"
        link.symlink_to(real_dir)

        guard = AccountPathGuard(tmp_path, "user-001")
        with pytest.raises(PathGuardError) as exc_info:
            guard.resolve_write("skills/evil-skill/SKILL.md")
        assert exc_info.value.code == ERR_SYMLINK


# ---------------------------------------------------------------------------
# Account ID validation
# ---------------------------------------------------------------------------

class TestAccountIdValidation:
    def test_empty_account_id(self, tmp_path: Path):
        with pytest.raises(ValueError):
            AccountPathGuard(tmp_path, "")

    def test_dot_account_id(self, tmp_path: Path):
        with pytest.raises(ValueError):
            AccountPathGuard(tmp_path, "..")

    def test_slash_in_account_id(self, tmp_path: Path):
        with pytest.raises(ValueError):
            AccountPathGuard(tmp_path, "user/001")
