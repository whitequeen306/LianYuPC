"""策略预过滤：先拒后允、上下文升级、来源护栏。

注意：这里的判定**不是**权威闸门（权威在 Electron `policy.js`），
但如果这一层漏判，模型会拿到「允许」的错觉并继续尝试。所以每条规则都要有测试。
"""

from __future__ import annotations

import json

import app.tools  # noqa: F401 - 导入即注册
from app.capability.policy import (
    DEFAULT_POLICY,
    EVIDENCE_SCREEN,
    EVIDENCE_USER,
    check_evidence,
    decide,
    escalate_danger,
    is_sensitive_process,
    is_terminal_process,
    label_hits_high_risk,
    load_policy,
)
from app.tools.registry import get_tool


def test_load_policy_reads_bundled_file():
    policy = load_policy()
    assert policy["version"] >= 1
    assert "default" in policy and policy["default"]["maxDanger"] in {"R0", "R1", "R2", "R3"}
    assert policy["sensitiveProcesses"]


def test_load_policy_falls_back_on_broken_json(tmp_path):
    bad = tmp_path / "policy.json"
    bad.write_text("{ not json", encoding="utf-8")
    assert load_policy(bad)["default"]["maxDanger"] == DEFAULT_POLICY["default"]["maxDanger"]


def test_local_tool_always_allowed():
    spec = get_tool("get_current_time")
    decision = decide(spec, {})
    assert decision.allowed and not decision.needs_confirm


def test_terminal_detection():
    assert is_terminal_process("powershell.exe")
    assert is_terminal_process("C:\\Windows\\System32\\cmd.exe")
    assert not is_terminal_process("chrome.exe")
    assert not is_terminal_process(None)


def test_sensitive_process_detection():
    assert is_sensitive_process("1Password.exe")
    assert is_sensitive_process("某银行bank.exe")
    assert not is_sensitive_process("notepad.exe")


def test_label_high_risk_words():
    assert label_hits_high_risk("删除全部")
    assert label_hits_high_risk("确认支付")
    assert not label_hits_high_risk("播放")


def test_type_into_terminal_escalates_to_r3():
    spec = get_tool("desk_type")
    assert spec.danger == "R2"
    assert escalate_danger(spec, {"text": "dir"}, foreground_process="powershell.exe") == "R3"
    assert escalate_danger(spec, {"text": "hi"}, foreground_process="notepad.exe") == "R2"


def test_click_high_risk_label_escalates():
    spec = get_tool("desk_click")
    assert escalate_danger(spec, {"label": "删除账号"}) == "R3"
    assert escalate_danger(spec, {"label": "播放"}) == "R2"


def test_screen_evidence_never_triggers_r3():
    """屏幕上的字不是指令：R3 + screen_content 一律拒绝。"""
    spec = get_tool("desk_click")
    decision = decide(spec, {"label": "转账", "evidence": EVIDENCE_SCREEN})
    assert not decision.allowed
    assert decision.reason == "screen_content_high_risk"
    assert decision.danger == "R3"


def test_user_evidence_allows_r3_with_confirm():
    spec = get_tool("desk_click")
    decision = decide(spec, {"label": "转账", "evidence": EVIDENCE_USER})
    assert decision.allowed
    assert decision.needs_confirm is True


def test_role_ceiling_blocks_high_danger():
    spec = get_tool("desk_click")
    decision = decide(spec, {"label": "删除", "evidence": EVIDENCE_USER}, role="pet_gentle")
    assert decision.allowed is False
    assert decision.reason == "policy_blocked"


def test_sensitive_foreground_denies_everything():
    spec = get_tool("desk_screen_read")
    decision = decide(spec, {"evidence": EVIDENCE_USER}, foreground_process="KeePassXC.exe")
    assert decision.allowed is False
    assert decision.reason == "sensitive_window"


def test_low_risk_auto_confirm_toggle_only_affects_r2():
    click = get_tool("desk_click")
    assert decide(click, {"label": "播放", "evidence": EVIDENCE_USER},
                  auto_confirm_low_risk=True).needs_confirm is False
    r3 = decide(click, {"label": "删除", "evidence": EVIDENCE_USER},
                auto_confirm_low_risk=True)
    assert r3.needs_confirm is True, "R3 永远不能被免确认开关放过"


def test_check_evidence_returns_none_for_low_danger():
    spec = get_tool("desk_screen_read")
    assert check_evidence(spec, "R0", {"evidence": EVIDENCE_SCREEN}) is None


def test_policy_file_is_valid_json():
    from app.capability.policy import POLICY_PATH

    payload = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    assert payload["appDenylist"], "denylist 为空会让高危应用可被启动"
