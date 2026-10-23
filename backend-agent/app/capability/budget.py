"""桌面任务预算：步数 / 视觉帧 / 墙钟 / 连续失败 —— 四重上限，全部硬性。

对应审计 A4「工具循环无上限」的同一条红线：**任何自动化都必须有界**。
外借引擎时代靠 `call-timeout-ms: 630000` 兜底（审计 A5 点名），
现在把「一次长阻塞」拆成「多次短步 + 明确预算」，到点即降级收尾而不是硬等。

预算按「任务」计，一个任务 = 一次用户消息触发的一串桌面动作。
它比 `max_tool_rounds` 更细：那数的是模型↔工具往返轮次，这里数的是桌面动作个数。
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field

from app.config import get_settings

# 终止原因（回灌给模型时翻成中文，模型再翻成角色台词）
STOP_STEPS = "step_budget_exhausted"
STOP_VISION = "vision_budget_exhausted"
STOP_DEADLINE = "task_deadline"
STOP_FAILURES = "repeated_failure"


def new_session_id(user_id: int | None) -> str:
    """一次用户回合一个会话；`stepId` 的幂等去重以它为前缀。"""
    return f"desk-{user_id if user_id is not None else 0}-{uuid.uuid4().hex[:8]}"


@dataclass
class TaskBudget:
    """单任务预算。由 `chat_turn._tool_context()` 创建，随 tool_context 传给 handler。"""

    session_id: str
    max_steps: int = 6
    max_vlm_frames: int = 4
    deadline_seconds: float = 90.0
    max_consecutive_failures: int = 2
    started_at: float = field(default_factory=time.monotonic)
    steps: int = 0
    vlm_frames: int = 0
    consecutive_failures: dict[str, int] = field(default_factory=dict)
    last_error: str = ""

    # ── 构造 ──────────────────────────────────────────────────────────────

    @classmethod
    def from_settings(cls, session_id: str) -> "TaskBudget":
        settings = get_settings()
        return cls(
            session_id=session_id,
            max_steps=settings.desktop_max_steps,
            max_vlm_frames=settings.desktop_max_vlm_frames,
            deadline_seconds=settings.desktop_task_deadline_seconds,
            max_consecutive_failures=settings.desktop_max_consecutive_failures,
        )

    # ── 状态 ──────────────────────────────────────────────────────────────

    def elapsed_seconds(self) -> float:
        return time.monotonic() - self.started_at

    def remaining_seconds(self) -> float:
        return max(0.0, self.deadline_seconds - self.elapsed_seconds())

    def blocked_reason(self, tool: str = "") -> str | None:
        """能否再走一步；不能则返回终止原因（None = 允许）。"""
        if self.steps >= self.max_steps:
            return STOP_STEPS
        if self.remaining_seconds() <= 0:
            return STOP_DEADLINE
        if tool and self.consecutive_failures.get(tool, 0) >= self.max_consecutive_failures:
            return STOP_FAILURES
        return None

    # ── 记账 ──────────────────────────────────────────────────────────────

    def begin_step(self) -> str:
        """占用一步并返回幂等的 `stepId`（`{session}#{n}`）。

        幂等语义：同一步在网络重试时重发同一个 stepId，Java 与 Electron 两侧都按它去重，
        保证「重试绝不产生第二次点击」。
        """
        self.steps += 1
        return f"{self.session_id}#{self.steps}"

    def can_use_vision(self) -> bool:
        return self.vlm_frames < self.max_vlm_frames

    def note_vision(self) -> None:
        self.vlm_frames += 1

    def note_result(self, tool: str, ok: bool, error_code: str = "") -> None:
        if ok:
            self.consecutive_failures.pop(tool, None)
            return
        self.consecutive_failures[tool] = self.consecutive_failures.get(tool, 0) + 1
        self.last_error = error_code

    def snapshot(self) -> dict[str, object]:
        return {
            "sessionId": self.session_id,
            "steps": self.steps,
            "maxSteps": self.max_steps,
            "vlmFrames": self.vlm_frames,
            "remainingSeconds": round(self.remaining_seconds(), 1),
            "lastError": self.last_error,
        }


__all__ = [
    "STOP_DEADLINE",
    "STOP_FAILURES",
    "STOP_STEPS",
    "STOP_VISION",
    "TaskBudget",
    "new_session_id",
]
