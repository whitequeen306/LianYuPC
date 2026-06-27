# Generate self-signed TLS cert for api-gateway (local Docker / dev).
# Output: certs/server.crt + certs/server.key at repo root.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$certsDir = Join-Path $root "certs"
New-Item -ItemType Directory -Force -Path $certsDir | Out-Null

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if (-not $openssl) {
    $gitOpenssl = "C:\Program Files\Git\usr\bin\openssl.exe"
    if (Test-Path $gitOpenssl) {
        $openssl = Get-Command $gitOpenssl
    }
}
if (-not $openssl) {
    Write-Error "openssl not found. Install OpenSSL or Git for Windows (includes openssl)."
}

$key = Join-Path $certsDir "server.key"
$cert = Join-Path $certsDir "server.crt"

& $openssl.Source req -x509 -nodes -newkey rsa:2048 `
    -keyout $key `
    -out $cert `
    -days 365 `
    -subj "/CN=localhost/O=LianYu-PC/C=CN" `
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

Write-Host "Created:"
Write-Host "  $cert"
Write-Host "  $key"
Write-Host "Do not commit private keys. See certs/README.md."
