# CodeCortexLoop Summary

**Preset:** deep  
**Scope:** whole project  
**Mode:** Report  
**Generated:** 2026-06-22T09:25:56.826Z

## Health Score

| Category | Score |
|----------|-------|
| **Overall** | **32** |
| Correctness | 0 |
| Security | 55 |
| Error handling | 0 |
| Performance | 22 |
| Tests | 0 |
| Simplicity | 39 |
| Cleanup | 81 |

## Counts

| Severity | Count |
|----------|-------|
| Critical | 9 |
| High | 32 |
| Medium | 31 |
| Low | 9 |
| **Total** | **81** |

## Top Priorities

1. **CL-010** — SSE partial save on stream error (Critical correctness)
2. **CL-001** — Captcha expression leak (High security)
3. **CL-023/024** — Silent chat polling & notification failures (Critical error-handling)
4. **CL-050–055** — Zero coverage on auth/SSE/group/memory paths (Critical tests)
5. **CL-038/042** — Memory list N+1 & Moments comment polling storm (High performance)

## Next Steps

Report mode complete. Review `docs/cortexloop/report.json` and category markdown files.  
To apply fixes, re-run with **Direct** mode or specify finding IDs (e.g. CL-001, CL-010).
