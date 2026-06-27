# TLS certificates for `api-gateway`

`docker-compose.yml` mounts this directory into the nginx container as `/etc/nginx/certs`.

## Local development

Generate a self-signed certificate (365 days, CN=localhost):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/generate-dev-certs.ps1
```

Or on Linux/macOS:

```bash
./scripts/generate-dev-certs.sh
```

Required files:

| File | Purpose |
|------|---------|
| `server.crt` | Public certificate |
| `server.key` | Private key |

## Production

Replace `server.crt` and `server.key` with your real certificate (e.g. from your CA or cloud provider). Keep the same filenames so nginx config does not need changes.

## Git

`server.crt` and `server.key` are **gitignored**. Only this README is tracked. Never commit private keys.
