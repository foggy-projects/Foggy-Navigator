"""Tests for nested Skill invocation: invoke_child_skill, call stack, depth protection."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import FrameStatus
from langgraph_biz_worker.runtime.skill_runtime import (
    MaxNestingDepthExceeded,
    SkillRuntime,
)


@pytest.fixture
def runtime():
    return SkillRuntime(max_nesting_depth=3)


class TestInvokeChildSkill:
    def test_basic_child_invocation(self, runtime):
        parent_fid = runtime.invoke_skill("t1", "parent_skill")
        child_fid = runtime.invoke_child_skill(parent_fid, "child_skill", {"key": "val"})

        parent = runtime.get_frame(parent_fid)
        child = runtime.get_frame(child_fid)

        assert parent.status == FrameStatus.WAITING_CHILD
        assert child.status == FrameStatus.RUNNING
        assert child.parent_frame_id == parent_fid
        assert child_fid in parent.child_frame_ids

    def test_child_input_passed(self, runtime):
        parent_fid = runtime.invoke_skill("t1", "parent_skill")
        child_fid = runtime.invoke_child_skill(parent_fid, "child_skill", {"order_id": "ORD-1"})

        child = runtime.get_frame(child_fid)
        assert child.input == {"order_id": "ORD-1"}


class TestCompleteChildAndResumeParent:
    def test_standard_completion(self, runtime):
        parent_fid = runtime.invoke_skill("t1", "parent_skill")
        child_fid = runtime.invoke_child_skill(parent_fid, "child_skill")

        # Submit child result
        runtime.submit_result(child_fid, "done", {"result": "ok"})

        # Complete child and resume parent
        promoted = runtime.complete_child_and_resume_parent(child_fid)

        assert "result_summary" in promoted or "structured_output" in promoted
        parent = runtime.get_frame(parent_fid)
        assert parent.status == FrameStatus.RUNNING
        assert "child_results" in parent.private_working_state
        assert child_fid in parent.private_working_state["child_results"]

    def test_root_frame_raises(self, runtime):
        root_fid = runtime.invoke_skill("t1", "root_skill")
        runtime.submit_result(root_fid, "done", {"r": 1})

        with pytest.raises(ValueError, match="no parent"):
            runtime.complete_child_and_resume_parent(root_fid)


class TestCallStack:
    def test_root_frame_stack(self, runtime):
        fid = runtime.invoke_skill("t1", "skill_a")
        stack = runtime.get_call_stack(fid)
        assert len(stack) == 1
        assert stack[0].frame_id == fid

    def test_two_level_stack(self, runtime):
        parent_fid = runtime.invoke_skill("t1", "skill_a")
        child_fid = runtime.invoke_child_skill(parent_fid, "skill_b")

        stack = runtime.get_call_stack(child_fid)
        assert len(stack) == 2
        assert stack[0].frame_id == child_fid
        assert stack[1].frame_id == parent_fid

    def test_three_level_stack(self, runtime):
        a = runtime.invoke_skill("t1", "skill_a")
        b = runtime.invoke_child_skill(a, "skill_b")
        c = runtime.invoke_child_skill(b, "skill_c")

        stack = runtime.get_call_stack(c)
        assert len(stack) == 3
        assert [f.frame_id for f in stack] == [c, b, a]

    def test_nesting_depth(self, runtime):
        a = runtime.invoke_skill("t1", "skill_a")
        assert runtime.get_nesting_depth(a) == 0

        b = runtime.invoke_child_skill(a, "skill_b")
        assert runtime.get_nesting_depth(b) == 1

        c = runtime.invoke_child_skill(b, "skill_c")
        assert runtime.get_nesting_depth(c) == 2


class TestDepthProtection:
    def test_exceeds_max_depth(self):
        """max_nesting_depth=2 means max 2 levels of nesting (root=0, child=1, grandchild=2).
        Trying to invoke from depth=2 should fail."""
        runtime = SkillRuntime(max_nesting_depth=2)
        a = runtime.invoke_skill("t1", "skill_a")        # depth 0
        b = runtime.invoke_child_skill(a, "skill_b")      # depth 1
        c = runtime.invoke_child_skill(b, "skill_c")      # depth 2 → allowed

        # depth 3 should hit the limit
        with pytest.raises(MaxNestingDepthExceeded) as exc_info:
            runtime.invoke_child_skill(c, "skill_d")

        assert exc_info.value.max_depth == 2

    def test_exact_limit_allowed(self):
        """Invocation at exactly max_nesting_depth is allowed (limit applies to children beyond)."""
        runtime = SkillRuntime(max_nesting_depth=1)
        a = runtime.invoke_skill("t1", "skill_a")        # depth 0
        b = runtime.invoke_child_skill(a, "skill_b")      # depth 1 → allowed

        # depth 2 from b should fail
        with pytest.raises(MaxNestingDepthExceeded):
            runtime.invoke_child_skill(b, "skill_c")

    def test_within_limit(self):
        runtime = SkillRuntime(max_nesting_depth=3)
        a = runtime.invoke_skill("t1", "skill_a")
        b = runtime.invoke_child_skill(a, "skill_b")
        c = runtime.invoke_child_skill(b, "skill_c")  # depth 2, limit 3 → OK

        assert runtime.get_nesting_depth(c) == 2

    def test_default_limit_is_5(self):
        runtime = SkillRuntime()
        assert runtime.max_nesting_depth == 5


class TestMultiChildSequence:
    """Parent invokes multiple children sequentially."""

    def test_two_sequential_children(self, runtime):
        parent = runtime.invoke_skill("t1", "parent_skill")

        # First child
        c1 = runtime.invoke_child_skill(parent, "child_1")
        runtime.submit_result(c1, "c1 done", {"c1": True})
        runtime.complete_child_and_resume_parent(c1)

        # Parent is RUNNING again
        assert runtime.get_frame(parent).status == FrameStatus.RUNNING

        # Second child
        c2 = runtime.invoke_child_skill(parent, "child_2")
        runtime.submit_result(c2, "c2 done", {"c2": True})
        runtime.complete_child_and_resume_parent(c2)

        # Parent should have both child results
        parent_frame = runtime.get_frame(parent)
        child_results = parent_frame.private_working_state["child_results"]
        assert c1 in child_results
        assert c2 in child_results
