#!/usr/bin/env python3
"""Inspect login captcha DOM dimensions via Playwright."""
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
        body = json.loads(resp.read().decode())
    return body


def main() -> None:
    payload = fetch_captcha_payload()
    print("api", payload.get("code"), "b64_len", len(payload.get("data", {}).get("imageBase64", "")))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        def handle(route):
            route.fulfill(
                status=200,
                content_type="application/json",
                body=json.dumps(payload),
            )

        page.route("**/api/auth/captcha", handle)
        page.goto(LOGIN_URL, wait_until="networkidle")
        page.wait_for_timeout(1500)

        info = page.evaluate(
            """() => {
              const img = document.querySelector('.captcha-image');
              const expr = document.querySelector('.captcha-expr');
              const card = document.querySelector('.captcha-card');
              return {
                hasImg: !!img,
                hasExpr: !!expr,
                exprText: expr?.textContent ?? '',
                imgOffsetW: img?.offsetWidth ?? 0,
                imgOffsetH: img?.offsetHeight ?? 0,
                imgNaturalW: img?.naturalWidth ?? 0,
                imgNaturalH: img?.naturalHeight ?? 0,
                imgComplete: img?.complete ?? false,
                srcLen: img?.src?.length ?? 0,
                cardW: card?.offsetWidth ?? 0,
                imgDisplay: img ? getComputedStyle(img).display : null,
                imgFlexShrink: img ? getComputedStyle(img).flexShrink : null,
              };
            }"""
        )
        print("dom", json.dumps(info, ensure_ascii=False))
        page.screenshot(path="debug-captcha-login.png", full_page=False)
        browser.close()


if __name__ == "__main__":
    main()
