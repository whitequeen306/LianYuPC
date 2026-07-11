from pathlib import Path
from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
ARTIFACT_DIR = ROOT / 'artifacts' / 'playwright'
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
SCREENSHOT_PATH = ARTIFACT_DIR / 'moments-thread.png'
APP_ORIGIN = 'http://127.0.0.1:4173'
APP_URL = f'{APP_ORIGIN}/#/app/moments'


def ok(data):
    return {'code': 200, 'message': 'ok', 'data': data}


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={'width': 1440, 'height': 1600})
        page.on('console', lambda msg: print(f'console:{msg.type}:{msg.text}'))
        page.on('pageerror', lambda err: print(f'pageerror:{err}'))

        def handle_route(route):
            url = route.request.url

            if url.endswith('/api/character'):
                route.fulfill(json=ok([
                    {
                        'id': 101,
                        'name': '阿宁',
                        'avatarUrl': '',
                        'avatarThumbUrl': '',
                        'settings': {}
                    },
                    {
                        'id': 201,
                        'name': '小雪',
                        'avatarUrl': '',
                        'avatarThumbUrl': '',
                        'settings': {}
                    },
                    {
                        'id': 202,
                        'name': '小雨',
                        'avatarUrl': '',
                        'avatarThumbUrl': '',
                        'settings': {}
                    }
                ]))
                return

            if url.endswith('/api/auth/me'):
                route.fulfill(json=ok({
                    'userId': 1,
                    'username': 'tester',
                    'nickname': '测试用户',
                    'avatarUrl': ''
                }))
                return

            if url.endswith('/api/conversation'):
                route.fulfill(json=ok([]))
                return

            if '/api/notifications' in url:
                route.fulfill(json=ok([]))
                return

            if url.endswith('/api/notifications/mark-read'):
                route.fulfill(json=ok(None))
                return

            if url.endswith('/api/character-state/states'):
                route.fulfill(json=ok([]))
                return

            if '/api/character-state/diaries' in url:
                route.fulfill(json=ok([]))
                return

            if url.endswith('/api/auth/refresh'):
                route.fulfill(json=ok({'token': 'playwright-token'}))
                return

            if url.endswith('/api/moments/mark-seen'):
                route.fulfill(json=ok(None))
                return

            if '/api/moments/1/comments' in url:
                route.fulfill(json=ok({
                    'items': [
                        {
                            'id': 1,
                            'postId': 1,
                            'authorType': 'CHARACTER',
                            'characterId': 201,
                            'characterName': '小雪',
                            'characterAvatarUrl': '',
                            'characterAvatarThumbUrl': '',
                            'userDisplayName': None,
                            'parentId': None,
                            'rootId': 1,
                            'content': '今天的夕阳真的很好看',
                            'sourceType': 'AUTO_AUTHOR_REPLY',
                            'createdAt': '2026-07-11T10:00:00'
                        },
                        {
                            'id': 2,
                            'postId': 1,
                            'authorType': 'CHARACTER',
                            'characterId': 202,
                            'characterName': '小雨',
                            'characterAvatarUrl': '',
                            'characterAvatarThumbUrl': '',
                            'userDisplayName': None,
                            'parentId': None,
                            'rootId': 2,
                            'content': '照片里的风有种夏天的味道',
                            'sourceType': 'AUTO_AUTHOR_REPLY',
                            'createdAt': '2026-07-11T10:01:00'
                        },
                        {
                            'id': 3,
                            'postId': 1,
                            'authorType': 'CHARACTER',
                            'characterId': 101,
                            'characterName': '阿宁',
                            'characterAvatarUrl': '',
                            'characterAvatarThumbUrl': '',
                            'userDisplayName': None,
                            'parentId': 1,
                            'rootId': 1,
                            'content': '你喜欢的话，下次我拍给你看海边那一段。',
                            'sourceType': 'AUTO_AUTHOR_REPLY',
                            'createdAt': '2026-07-11T10:02:00'
                        },
                        {
                            'id': 4,
                            'postId': 1,
                            'authorType': 'CHARACTER',
                            'characterId': 101,
                            'characterName': '阿宁',
                            'characterAvatarUrl': '',
                            'characterAvatarThumbUrl': '',
                            'userDisplayName': None,
                            'parentId': 2,
                            'rootId': 2,
                            'content': '被你这么一说，我都想把那阵风装进口袋里了。',
                            'sourceType': 'AUTO_AUTHOR_REPLY',
                            'createdAt': '2026-07-11T10:03:00'
                        }
                    ],
                    'nextCursor': None,
                    'hasMore': False,
                    'totalCount': 4
                }))
                return

            if '/api/moments' in url:
                route.fulfill(json=ok({
                    'items': [
                        {
                            'id': 1,
                            'characterId': 101,
                            'characterName': '阿宁',
                            'characterAvatarUrl': '',
                            'characterAvatarThumbUrl': '',
                            'authorType': 'CHARACTER',
                            'content': '刚刚路过江边的时候，天色像被蜜桃汽水泡过一样。',
                            'postType': 'MOOD',
                            'commentCount': 4,
                            'conversationId': 88,
                            'createdAt': '2026-07-11T09:58:00'
                        }
                    ],
                    'nextCursor': None,
                    'hasMore': False
                }))
                return

            route.continue_()

        page.route('**/api/**', handle_route)

        page.add_init_script(
            f"""
            window.electronAPI = {{
              isElectron: true,
              bootstrapAuthToken: async () => 'playwright-token',
              getRuntimeConfig: async () => ({{ apiOrigin: '{APP_ORIGIN}' }})
            }};
            localStorage.setItem('lianyu-user-profile', JSON.stringify({{
              userId: 1,
              username: 'tester',
              nickname: '测试用户',
              avatarUrl: ''
            }}));
            """
        )

        page.goto(APP_URL)
        page.wait_for_load_state('networkidle')
        print(f'final-url:{page.url}')
        page.wait_for_selector('.feed-card')
        page.locator('.feed-card .feed-action-btn').first.click()
        page.wait_for_selector('.feed-comment-reply-list')
        page.screenshot(path=str(SCREENSHOT_PATH), full_page=True)

        text = page.locator('.feed-comment-zone').inner_text()
        assert '阿宁 回复 小雪' in text
        assert '阿宁 回复 小雨' in text

        browser.close()

        print(SCREENSHOT_PATH)


if __name__ == '__main__':
    main()
