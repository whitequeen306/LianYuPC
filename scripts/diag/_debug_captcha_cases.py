#!/usr/bin/env python3
"""Simulate old vs new captcha API against current LoginPage."""
import json
import ssl
import urllib.request

from playwright.sync_api import sync_playwright

CAPTCHA_URL = "https://154.219.111.30/api/auth/captcha"
LOGIN_URL = "http://localhost:5173/#/login"


def fetch_captcha_payload():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(CAPTCHA_URL, context=ctx, timeout=15) as resp:
        return json.loads(resp.read().decode())


def run_case(name: str, mutate) -> None:
    payload = fetch_captcha_payload()
    mutate(payload)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.route("**/api/auth/captcha", lambda route: route.fulfill(
            status=200, content_type="application/json", body=json.dumps(payload)
        ))
        page.goto(LOGIN_URL, wait_until="networkidle")
        page.wait_for_timeout(1200)
        info = page.evaluate("""() => ({
          hasImg: !!document.querySelector('.captcha-image'),
          exprText: document.querySelector('.captcha-expr')?.textContent ?? '',
          imgW: document.querySelector('.captcha-image')?.offsetWidth ?? 0,
        })""")
        print(name, json.dumps(info, ensure_ascii=False))
        browser.close()


def main() -> None:
    run_case("new_api", lambda p: None)

    def old_api_shape(p):
        data = p.get("data", {})
        data["expression"] = "12 + 3 = ?"
        data.pop("imageBase64", None)
        p["data"] = data

    run_case("old_api_no_image", old_api_shape)

    def only_id(p):
        p["data"] = {"captchaId": p["data"]["captchaId"]}

    run_case("missing_fields", only_id)


if __name__ == "__main__":
    main()
