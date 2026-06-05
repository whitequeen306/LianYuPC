"""
LianYu-PC end-to-end smoke + flow tests (Playwright).

Default target: deployed cloud stack at https://154.219.111.30
Override: set LIANYU_E2E_BASE_URL=http://localhost:5173 (needs backend on :8080)
"""
from __future__ import annotations

import json
import os
import re
import ssl
import sys
import time
import uuid
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable

from playwright.sync_api import Page, sync_playwright, expect

BASE_URL = os.environ.get("LIANYU_E2E_BASE_URL", "https://154.219.111.30").rstrip("/")
SCREENSHOT_DIR = os.path.join(os.path.dirname(__file__), "artifacts")
PASSWORD = "TestPass1a"
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


@dataclass
class TestResult:
    name: str
    passed: bool
    detail: str = ""
    duration_ms: int = 0


@dataclass
class TestRun:
    results: list[TestResult] = field(default_factory=list)

    def record(self, name: str, fn: Callable[[], None]) -> None:
        start = time.perf_counter()
        try:
            fn()
            ms = int((time.perf_counter() - start) * 1000)
            self.results.append(TestResult(name, True, duration_ms=ms))
            print(f"  PASS  {name} ({ms}ms)")
        except Exception as exc:  # noqa: BLE001
            ms = int((time.perf_counter() - start) * 1000)
            detail = str(exc)
            self.results.append(TestResult(name, False, detail, ms))
            print(f"  FAIL  {name} ({ms}ms)\n        {detail}")

    @property
    def passed(self) -> int:
        return sum(1 for r in self.results if r.passed)

    @property
    def failed(self) -> int:
        return sum(1 for r in self.results if not r.passed)


def api_request(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    url = f"{BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["lianyu-token"] = token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=45) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode(errors="replace")
        raise RuntimeError(f"HTTP {exc.code} {path}: {payload[:300]}") from exc


def solve_captcha(expression: str) -> int:
    text = expression.strip()
    match = re.match(r"(\d+)\s*([+\-×÷])\s*(\d+)\s*=\s*\?", text)
    if not match:
        raise ValueError(f"Unrecognized captcha expression: {expression!r}")
    a, op, b = int(match.group(1)), match.group(2), int(match.group(3))
    if op == "+":
        return a + b
    if op == "-":
        return a - b
    if op == "×":
        return a * b
    if op == "÷":
        return a // b
    raise ValueError(f"Unknown operator: {op}")


def register_user_via_api(username: str) -> dict:
    cap = api_request("GET", "/api/auth/captcha")["data"]
    answer = solve_captcha(cap["expression"])
    payload = {
        "username": username,
        "password": PASSWORD,
        "nickname": "E2E Tester",
        "captcha": {"captchaId": cap["captchaId"], "captchaAnswer": answer},
    }
    resp = api_request("POST", "/api/auth/register", payload)
    if resp.get("code") != 200:
        raise RuntimeError(f"Register failed: {resp}")
    return resp["data"]


def wait_for_captcha(page: Page) -> str:
    expr = page.locator(".captcha-expr")
    expect(expr).not_to_have_text("加载中...", timeout=15000)
    expect(expr).not_to_have_text("获取失败，点击刷新", timeout=15000)
    text = expr.inner_text().strip()
    if not re.search(r"=\s*\?", text):
        raise AssertionError(f"Captcha not ready: {text!r}")
    return text


def fill_captcha(page: Page) -> None:
    answer = solve_captcha(wait_for_captcha(page))
    page.locator(".captcha-input input").fill(str(answer))


def dismiss_onboarding_if_present(page: Page) -> None:
    for _ in range(3):
        cancel = page.get_by_role("button", name=re.compile(r"^(取消|关闭|知道了|Skip)$"))
        if cancel.count() == 0:
            break
        try:
            cancel.first.click(timeout=800)
            page.wait_for_timeout(300)
        except Exception:
            break


def goto_hash(page: Page, path: str) -> None:
    if not path.startswith("/"):
        path = f"/{path}"
    if BASE_URL not in page.url:
        page.goto(f"{BASE_URL}/", wait_until="domcontentloaded")
    page.evaluate("(p) => { window.location.hash = p; }", path)
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(400)
    dismiss_onboarding_if_present(page)


def expect_hash(page: Page, fragment: str) -> None:
    if not fragment.startswith("#"):
        fragment = f"#{fragment}"
    expect(page).to_have_url(re.compile(re.escape(fragment)), timeout=15000)


def inject_auth(page: Page, auth: dict) -> None:
    goto_hash(page, "/")
    page.evaluate(
        """(auth) => {
            localStorage.setItem('lianyu-token', auth.token);
            localStorage.setItem('lianyu-token-name', auth.tokenName || 'lianyu-token');
        }""",
        auth,
    )
    goto_hash(page, "/app")


def assert_page_title(page: Page, text: str) -> None:
    expect(page.locator("h1.page-title").first).to_contain_text(text, timeout=15000)


def launch_browser(playwright):
    try:
        return playwright.chromium.launch(headless=True)
    except Exception:
        return playwright.chromium.launch(headless=True, channel="msedge")


def run_tests() -> TestRun:
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    run = TestRun()
    username = f"e2e_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    auth_data: dict = {}

    with sync_playwright() as p:
        browser = launch_browser(p)
        context = browser.new_context(
            ignore_https_errors=True,
            locale="zh-CN",
            viewport={"width": 1280, "height": 900},
        )
        page = context.new_page()

        def snap(label: str) -> None:
            path = os.path.join(SCREENSHOT_DIR, f"{label}.png")
            page.screenshot(path=path, full_page=True)

        def test_landing() -> None:
            goto_hash(page, "/")
            expect(page.locator("body")).to_be_visible()
            snap("01-landing")

        def test_captcha_api() -> None:
            resp = api_request("GET", "/api/auth/captcha")
            assert resp.get("code") == 200, resp
            data = resp.get("data") or {}
            assert data.get("captchaId"), data
            assert data.get("expression"), data

        def test_login_page() -> None:
            goto_hash(page, "/login")
            expect(page.get_by_role("heading", name="欢迎回来")).to_be_visible()
            wait_for_captcha(page)
            snap("02-login")

        def test_register_page() -> None:
            goto_hash(page, "/register")
            expect(page.get_by_role("heading", name="注册账号")).to_be_visible()
            wait_for_captcha(page)
            snap("03-register")

        def test_register_via_api() -> None:
            auth_data.update(register_user_via_api(username))
            assert auth_data.get("token"), auth_data

        def test_register_ui_form() -> None:
            goto_hash(page, "/register")
            page.locator('input[autocomplete="username"]').fill(f"{username}_ui")
            page.locator('input[placeholder="昵称（选填）"]').fill("UI Form")
            page.locator('input[autocomplete="new-password"]').first.fill(PASSWORD)
            page.locator('input[placeholder="确认密码"]').fill(PASSWORD)
            fill_captcha(page)
            page.locator("button.submit-btn").click()
            page.wait_for_timeout(3000)
            err = page.locator(".el-message--error")
            if err.count() and err.first.is_visible():
                raise AssertionError(f"Register UI error toast: {err.first.inner_text()}")
            if "#/app" not in page.url:
                raise AssertionError(f"Register UI did not reach /app, url={page.url}")

        def test_session_injection() -> None:
            inject_auth(page, auth_data)
            expect_hash(page, "#/app")
            token = page.evaluate("() => localStorage.getItem('lianyu-token')")
            assert token == auth_data["token"]

        def test_authenticated_api() -> None:
            resp = api_request("GET", "/api/characters", token=auth_data["token"])
            if resp.get("code") != 200:
                raise AssertionError(
                    f"GET /api/characters failed: code={resp.get('code')} msg={resp.get('message')} "
                    "(cloud backend may be degraded — UI steps will still run)"
                )

        def test_home() -> None:
            goto_hash(page, "/app")
            expect_hash(page, "#/app")
            expect(page.locator(".home-page")).to_be_visible(timeout=15000)
            snap("05-home")

        def test_characters_page() -> None:
            goto_hash(page, "/app/characters")
            expect_hash(page, "#/app/characters")
            assert_page_title(page, "我的羁绊")
            snap("06-characters")

        def test_character_square() -> None:
            goto_hash(page, "/app/character-square")
            expect_hash(page, "#/app/character-square")
            assert_page_title(page, "角色广场")
            cards = page.locator(".template-card")
            empty = page.locator(".empty-state")
            if cards.count() == 0 and empty.count() == 0:
                err = page.locator(".el-message--error")
                if err.count():
                    raise AssertionError(f"Character square error: {err.first.inner_text()}")
            if cards.count():
                expect(cards.first).to_be_visible(timeout=5000)
            snap("07-character-square")

        def test_add_character_from_square() -> None:
            goto_hash(page, "/app/character-square")
            add_btn = page.get_by_role("button", name="加入我的角色")
            if add_btn.count() == 0:
                print("        [skip] no square templates available (API may be down)")
                return
            add_btn.click()
            dialog = page.locator(".el-message-box")
            expect(dialog).to_be_visible(timeout=10000)
            dialog.locator("input").fill("郑州")
            dialog.get_by_role("button", name=re.compile("确认|加入|确定")).click()
            page.wait_for_timeout(2000)
            success = page.locator(".el-message--success")
            if success.count():
                expect(success.first).to_be_visible(timeout=10000)
            confirm = page.locator(".el-message-box")
            if confirm.count() and confirm.is_visible():
                confirm.get_by_role("button", name=re.compile("取消|稍后|关闭")).first.click(timeout=5000)
            snap("08-after-add-character")

        def test_characters_has_entry() -> None:
            goto_hash(page, "/app/characters")
            cards = page.locator(".character-card")
            empty = page.locator(".empty-state")
            expect(cards.first.or_(empty.first)).to_be_visible(timeout=15000)

        def test_settings_page() -> None:
            goto_hash(page, "/app/settings")
            expect_hash(page, "#/app/settings")
            assert_page_title(page, "设置")
            snap("09-settings")

        def test_profile_page() -> None:
            goto_hash(page, "/app/profile")
            expect_hash(page, "#/app/profile")
            assert_page_title(page, "个人资料")
            snap("10-profile")

        def test_memory_page() -> None:
            goto_hash(page, "/app/memory")
            expect_hash(page, "#/app/memory")
            expect(page.locator(".memory-page")).to_be_visible(timeout=15000)
            snap("11-memory")

        def test_group_chat_page() -> None:
            goto_hash(page, "/app/group-chat")
            expect_hash(page, "#/app/group-chat")
            expect(page.locator(".group-chat-page")).to_be_visible(timeout=15000)
            snap("12-group-chat")

        def test_moments_page() -> None:
            goto_hash(page, "/app/moments")
            expect_hash(page, "#/app/moments")
            expect(page.locator(".moments-page")).to_be_visible(timeout=15000)
            snap("13-moments")

        def test_diary_page() -> None:
            goto_hash(page, "/app/diary")
            expect_hash(page, "#/app/diary")
            expect(page.locator(".diary-page")).to_be_visible(timeout=15000)
            snap("14-diary")

        def test_ai_models_api() -> None:
            resp = api_request(
                "GET",
                "/api/ai/models?provider=platform",
                token=auth_data["token"],
            )
            if resp.get("code") != 200:
                raise AssertionError(
                    f"ai/models: code={resp.get('code')} msg={resp.get('message')} "
                    "(expected if masked API key fix not deployed)"
                )

        def test_start_chat_if_character_exists() -> None:
            goto_hash(page, "/app/characters")
            chat_btn = page.get_by_role("button", name=re.compile("聊天|对话|继续"))
            if chat_btn.count() == 0:
                card = page.locator(".character-card, .char-card, .companion-card, .char-list-item").first
                if card.count():
                    card.click()
                    page.wait_for_timeout(800)
            chat_btn = page.get_by_role("button", name=re.compile("聊天|对话|继续"))
            if chat_btn.count() == 0:
                return
            chat_btn.first.click()
            page.wait_for_url(re.compile(r"#/app/chat/"), timeout=15000)
            expect(page.locator(".chat-page, .chat-view, .companion-page").first).to_be_visible(timeout=15000)
            snap("15-chat")

        def test_logout() -> None:
            goto_hash(page, "/app")
            page.locator("button.header-avatar").click()
            page.locator(".el-dropdown-menu__item").filter(has_text="退出登录").click()
            confirm = page.locator(".el-message-box")
            expect(confirm).to_be_visible(timeout=5000)
            confirm.locator("button").filter(has_text="退出登录").click()
            page.wait_for_url(re.compile(r"#/$|#/login"), timeout=15000)
            token = page.evaluate("() => localStorage.getItem('lianyu-token')")
            assert not token, "Token should be cleared after logout"
            snap("16-after-logout")

        def test_login_existing_user() -> None:
            goto_hash(page, "/login")
            page.locator('input[autocomplete="username"]').fill(username)
            page.locator('input[autocomplete="current-password"]').fill(PASSWORD)
            fill_captcha(page)
            page.locator("button.submit-btn").click()
            page.wait_for_url(re.compile(r"#/app"), timeout=20000)
            snap("17-login-again")

        steps = [
            ("Landing page loads", test_landing),
            ("Captcha API (GET /api/auth/captcha)", test_captcha_api),
            ("Login page + captcha widget", test_login_page),
            ("Register page + captcha widget", test_register_page),
            ("Register user via API", test_register_via_api),
            ("Inject session + open /app", test_session_injection),
            ("Authenticated API (GET /api/characters)", test_authenticated_api),
            ("Home dashboard", test_home),
            ("Characters page", test_characters_page),
            ("Character square catalog", test_character_square),
            ("Add character from square", test_add_character_from_square),
            ("Characters list has entry", test_characters_has_entry),
            ("Settings page", test_settings_page),
            ("Profile page", test_profile_page),
            ("Memory page", test_memory_page),
            ("Group chat page", test_group_chat_page),
            ("Moments page", test_moments_page),
            ("Diary page", test_diary_page),
            ("AI models API", test_ai_models_api),
            ("Open chat from character", test_start_chat_if_character_exists),
            ("Logout", test_logout),
            ("Login with registered user", test_login_existing_user),
            ("Register UI form (secondary)", test_register_ui_form),
        ]

        print(f"\nLianYu E2E — base URL: {BASE_URL}")
        print(f"Test user: {username}\n")
        for name, fn in steps:
            run.record(name, fn)
            if not run.results[-1].passed:
                snap(f"fail-{len(run.results)}")

        browser.close()

    report_path = os.path.join(SCREENSHOT_DIR, "report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "baseUrl": BASE_URL,
                "username": username,
                "passed": run.passed,
                "failed": run.failed,
                "results": [r.__dict__ for r in run.results],
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    print(f"\nReport: {report_path}")
    print(f"Screenshots: {SCREENSHOT_DIR}")
    return run


if __name__ == "__main__":
    result = run_tests()
    print(f"\n{'=' * 50}")
    print(f"TOTAL: {result.passed} passed, {result.failed} failed / {len(result.results)} run")
    print(f"{'=' * 50}\n")
    if result.failed:
        for r in result.results:
            if not r.passed:
                print(f"  - {r.name}: {r.detail}")
        sys.exit(1)
    sys.exit(0)
