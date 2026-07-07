# Self-Hosted Updater Design

## Goal

Move the desktop client's automatic update path off the GitHub Releases proxy and onto LianYu's own public MinIO file source, while replacing the inline About-page updater status with a global update dialog.

## Decisions

1. The client update chain must not call `/api/public/updater/*` anymore.
2. GitHub Releases remains as a backup publishing channel only.
3. Update assets are served from existing public file infrastructure:
   - `/api/public/files/updates/latest.yml`
   - `/api/public/files/updates/LianYu-Setup-x.x.x.exe`
   - `/api/public/files/updates/LianYu-Setup-x.x.x.exe.blockmap`
4. The public file allowlist will add only `updates/latest.yml`, `updates/*.exe`, and `updates/*.exe.blockmap`; it will not allow arbitrary static files.
5. The global update dialog owns all visible updater states: update found, downloading, ready to install, installing, and error.
6. About page keeps only a compact manual “检查更新” entry.

## Download Speed Strategy

The current proxy path is `client -> backend/api-gateway -> GitHub Release asset`. This makes every client download depend on a live server-to-GitHub stream. The new path is `client -> api-gateway -> backend public file stream -> MinIO local object`, removing GitHub from the runtime download path.

This still downloads the full installer. True blockmap differential updates remain out of scope for this pass.

## Installation Experience

NSIS installation remains unchanged for stability. The UI will show an explicit installing state before the app exits so users know the app is intentionally closing and installing in the background.

## Deletions

Remove obsolete runtime update code:

- `backend/lianyu-web/src/main/java/com/lianyu/web/controller/UpdaterController.java`
- `lianyu.updater.github-token` runtime config
- `/api/public/updater/*` references in updater code and tests
- `electron-updater` dependency and esbuild external entry
- old updater proxy deployment script

Keep GitHub publishing scripts and `build.publish` as release backup infrastructure.

## Verification

1. Frontend tests verify updater state, self-hosted latest URL, progress speed, and installer launch.
2. Backend build verifies deletion of old controller and public files compile.
3. Full release flow verifies update assets are uploaded to MinIO `updates/`.
4. Manual test verifies v0.2.260 detects and downloads the next version from `/api/public/files/updates/latest.yml`.
