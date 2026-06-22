# Security Findings

**Count:** 9

## CL-001
- **Severity:** High
- **Location:** backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java:49
- **Problem:** Captcha API exposes plaintext expression, bypassing captcha control
- **Evidence:** GET /api/auth/captcha returns expression; client can compute answer without OCR
- **Confidence:** high
- **Recommendation:** Remove expression from API response; return only captchaId and imageBase64
- **Auto-fixable:** yes

## CL-002
- **Severity:** High
- **Location:** backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java:126
- **Problem:** Rate limits trust client X-Forwarded-For without trusted-proxy validation
- **Evidence:** resolveClientIp() reads X-Forwarded-For for login/register throttling; attacker rotates header to bypass limits
- **Confidence:** high
- **Recommendation:** Honor X-Forwarded-For only from configured trusted proxies
- **Auto-fixable:** needs-confirmation

## CL-003
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:177
- **Problem:** Chat image upload validates MIME only, not magic bytes
- **Evidence:** uploadChatImage stores raw stream; validateChatImage checks Content-Type only vs avatar re-encode path
- **Confidence:** high
- **Recommendation:** Route chat images through ImageUploadValidator.validateAndReencode()
- **Auto-fixable:** yes

## CL-004
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java:1054
- **Problem:** SSE errors forward raw exception messages to clients
- **Evidence:** resolveStreamErrorMessage returns e.getMessage() for non-BusinessException; sent in SSE error payload
- **Confidence:** high
- **Recommendation:** Map to generic user message; log details server-side only
- **Auto-fixable:** yes

## CL-005
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/notification/NotificationService.java:196
- **Problem:** Web Push endpoints not validated for SSRF when push enabled
- **Evidence:** upsertPushSubscription persists arbitrary endpoint; WebPushService POSTs to stored URL
- **Confidence:** high
- **Recommendation:** Allowlist known push providers; block private/metadata IPs
- **Auto-fixable:** needs-confirmation

## CL-006
- **Severity:** Medium
- **Location:** backend/lianyu-app/src/main/resources/application.yml:144
- **Problem:** API docs enabled by default without auth
- **Evidence:** lianyu.api-docs.enabled defaults true; Sa-Token exempts /doc.html and /v3/api-docs/**
- **Confidence:** high
- **Recommendation:** Default api-docs to false in production profiles
- **Auto-fixable:** yes

## CL-007
- **Severity:** Medium
- **Location:** backend/lianyu-web/src/main/java/com/lianyu/web/config/WebSocketConfig.java:69
- **Problem:** WebSocket STOMP allows wildcard origin by default
- **Evidence:** ws.allowed-origin-patterns defaults to *; CSWSH risk if token leaked
- **Confidence:** high
- **Recommendation:** Require explicit origin list in all deployed profiles
- **Auto-fixable:** yes

## CL-008
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java:212
- **Problem:** Vault decryption errors leak infrastructure details to users
- **Evidence:** BusinessException message references LIANYU_MASTER_KEY and seed script path
- **Confidence:** high
- **Recommendation:** Return generic AI configuration unavailable message
- **Auto-fixable:** yes

## CL-009
- **Severity:** Low
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/ai/DashScopeTtsService.java:115
- **Problem:** TTS failure logs full upstream response body
- **Evidence:** log.warn includes full HTTP body on non-2xx from DashScope
- **Confidence:** high
- **Recommendation:** Log status and truncated/redacted body only
- **Auto-fixable:** yes

