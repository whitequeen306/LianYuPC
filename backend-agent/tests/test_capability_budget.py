"""单任务预算：步数 / 视觉帧 / 墙钟 / 连续失败 —— 四重上限都必须真的拦住。"""

from __future__ import annotations

import time

from app.capability.budget import (
    STOP_DEADLINE,
    STOP_FAILURES,
    STOP_STEPS,
    TaskBudget,
    new_session_id,
)


def make_budget(**overrides) -> TaskBudget:
    base = dict(session_id="desk-1-abcd1234", max_steps=3, max_vlm_frames=2,
                deadline_seconds=90.0, max_consecutive_failures=2)
    base.update(overrides)
    return TaskBudget(**base)


def test_session_id_is_unique_and_prefixed():
    a = new_session_id(7)
    b = new_session_id(7)
    assert a.startswith("desk-7-")
    assert a != b


def test_step_id_is_idempotent_sequence():
    budget = make_budget()
    assert budget.begin_step() == "desk-1-abcd1234#1"
    assert budget.begin_step() == "desk-1-abcd1234#2"
    assert budget.steps == 2


def test_step_budget_blocks_after_limit():
    budget = make_budget(max_steps=2)
    assert budget.blocked_reason() is None
    budget.begin_step()
    budget.begin_step()
    assert budget.blocked_reason() == STOP_STEPS


def test_deadline_blocks_even_with_steps_left():
    budget = make_budget(deadline_seconds=0.01)
    time.sleep(0.02)
    assert budget.remaining_seconds() == 0.0
    assert budget.blocked_reason() == STOP_DEADLINE


def test_consecutive_failures_block_only_that_tool():
    budget = make_budget()
    budget.note_result("desk_click", ok=False, error_code="target_obscured")
    assert budget.blocked_reason("desk_click") is None  # 1 次还允许
    budget.note_result("desk_click", ok=False, error_code="target_obscured")
    assert budget.blocked_reason("desk_click") == STOP_FAILURES
    # 别的工具不受影响
    assert budget.blocked_reason("desk_window_list") is None


def test_success_resets_failure_counter():
    budget = make_budget()
    budget.note_result("desk_click", ok=False, error_code="x")
    budget.note_result("desk_click", ok=True)
    assert budget.blocked_reason("desk_click") is None


def test_vision_budget():
    budget = make_budget(max_vlm_frames=1)
    assert budget.can_use_vision()
    budget.note_vision()
    assert not budget.can_use_vision()
    budget.note_vision()
    assert budget.vlm_frames == 2


def test_snapshot_shape():
    budget = make_budget()
    budget.begin_step()
    snap = budget.snapshot()
    assert snap["sessionId"] == "desk-1-abcd1234"
    assert snap["steps"] == 1
    assert snap["maxSteps"] == 3
    assert snap["remainingSeconds"] <= 90.0


def test_from_settings_reads_config():
    budget = TaskBudget.from_settings("desk-x")
    assert budget.max_steps >= 1
    assert budget.deadline_seconds > 0
    assert budget.blocked_reason() is None
