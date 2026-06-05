# Re-encrypt DEFAULT api_key_vault rows with current LIANYU_MASTER_KEY from repo .env
$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$backend = Join-Path $repoRoot 'backend'
$keysFile = Join-Path $repoRoot 'secrets\platform-keys.txt'
$sqlFile = Join-Path $backend 'scripts\_vault_reseed.sql'

if (-not (Test-Path $keysFile)) {
    Write-Host "Missing $keysFile"
    Write-Host "Copy secrets\platform-keys.txt.example to secrets\platform-keys.txt and paste 10 API keys (one per line)."
    exit 1
}

$keyLines = @(Get-Content $keysFile | Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*#' })
if ($keyLines.Count -lt 1) {
    Write-Host "No API keys in $keysFile"
    exit 1
}
if ($keyLines.Count -gt 10) {
    Write-Host "ERROR: at most 10 keys for DEFAULT pool, got $($keyLines.Count)"
    exit 1
}
Write-Host "DEFAULT pool: $($keyLines.Count) active key(s), slots $($keyLines.Count + 1)-10 will be disabled."

# Load LIANYU_MASTER_KEY from .env
$envPath = Join-Path $repoRoot '.env'
if (Test-Path $envPath) {
    Get-Content $envPath | ForEach-Object {
        if ($_ -match '^\s*LIANYU_MASTER_KEY=(.+)\s*$') {
            $env:LIANYU_MASTER_KEY = $Matches[1].Trim()
        }
    }
}
if (-not $env:LIANYU_MASTER_KEY) {
    Write-Host 'LIANYU_MASTER_KEY not set in environment or .env'
    exit 2
}

# 与 platform-keys.txt 中 Key 来源一致（官方 Key 用 api.deepseek.com，中转 Key 用对应网关）
$env:PLATFORM_VAULT_BASE_URL = 'https://api.deepseek.com'
$env:PLATFORM_VAULT_MODEL = 'deepseek-v4-flash'

Push-Location $backend
mvn -q -pl lianyu-security compile -DskipTests
$cp = "lianyu-security\target\classes;" + (Get-Content lianyu-security\.tmp_jasypt_cp.txt -Raw)
New-Item -ItemType Directory -Force -Path scripts\out | Out-Null
javac -encoding UTF-8 -cp $cp scripts\SeedDefaultVaultPool.java -d scripts\out
java -cp "scripts\out;$cp" SeedDefaultVaultPool $keysFile $sqlFile
Pop-Location

# Apply via Docker MySQL (reads MYSQL_* from .env)
$mysqlUser = 'lianyu'
$mysqlPass = '1216cheng'
if (Test-Path $envPath) {
    Get-Content $envPath | ForEach-Object {
        if ($_ -match '^\s*MYSQL_USER=(.+)\s*$') { $mysqlUser = $Matches[1].Trim() }
        if ($_ -match '^\s*MYSQL_PASSWORD=(.+)\s*$') { $mysqlPass = $Matches[1].Trim() }
    }
}
$sqlContent = Get-Content $sqlFile -Raw
$sqlContent | docker exec -i lianyu-mysql mysql "-u$mysqlUser" "-p$mysqlPass" lianyu
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: mysql apply failed (exit $LASTEXITCODE). Is lianyu-mysql running?"
    exit $LASTEXITCODE
}
Remove-Item $sqlFile -Force
Write-Host 'DEFAULT vault pool reseeded. Restart backend and retry chat.'
