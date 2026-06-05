# LianYu E2E Tests (Playwright)

## Prerequisites

```bash
pip install playwright
python -m playwright install chromium   # or use system Edge (script auto-fallback)
```

## Run against cloud (default)

```bash
python scripts/e2e/lianyu_e2e.py
```

## Run against local dev (needs backend :8080 + `npm run dev`)

```powershell
$env:LIANYU_E2E_BASE_URL="http://localhost:5173"
python scripts/with_server.py --server "cd frontend && npm run dev" --port 5173 -- python scripts/e2e/lianyu_e2e.py
```

(Docker Compose stack must also be up for API calls.)

## Artifacts

- `scripts/e2e/artifacts/report.json` — machine-readable results
- `scripts/e2e/artifacts/*.png` — screenshots per step / failures
