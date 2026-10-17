<#
.SYNOPSIS
    统计 backend-agent 影子对比（SHADOW 模式）的漂移率。

.DESCRIPTION
    从 backend 容器日志里抓 `Shadow observe` / `Proactive shadow` 行，
    分别统计 match / DIFF / 双空，给出漂移率与最近若干条 DIFF 样例。

    LLM 输出天然非确定，DIFF 不等于故障；漂移率用于判断
    「Python 链路与 Java 链路的产出是否在同一量级/同一风格」。

.PARAMETER Tail
    只看最近多少行日志（默认 5000）。

.PARAMETER Samples
    打印多少条 DIFF 样例（默认 5）。

.EXAMPLE
    .\scripts\agent-shadow-report.ps1
    .\scripts\agent-shadow-report.ps1 -Tail 20000 -Samples 10
#>
param(
    [int]$Tail = 5000,
    [int]$Samples = 5
)

$ErrorActionPreference = 'Stop'

Write-Host "读取 lianyu-backend 最近 $Tail 行日志..." -ForegroundColor Cyan
# docker 会把 JAVA_TOOL_OPTIONS 之类的提示写到 stderr；PS 5.1 会把它变成 ErrorRecord，
# 在 $ErrorActionPreference='Stop' 下直接抛错。这里统一转成字符串。
$ErrorActionPreference = 'Continue'
$lines = & docker logs lianyu-backend --tail $Tail 2>&1 | ForEach-Object {
    if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
}
$ErrorActionPreference = 'Stop'

if (-not $lines) {
    Write-Host "没有日志。容器是否在运行？（docker compose ps backend）" -ForegroundColor Yellow
    exit 1
}

function Get-Stats {
    param([string]$Label, [string]$Pattern, [string]$MatchWord, [string]$DiffWord)

    $scoped = $lines | Where-Object { $_ -match $Pattern }
    $total = @($scoped).Count
    $match = @($scoped | Where-Object { $_ -match $MatchWord }).Count
    $diff = @($scoped | Where-Object { $_ -match $DiffWord }).Count
    $both = @($scoped | Where-Object { $_ -match 'both-null' }).Count

    Write-Host ""
    Write-Host "=== $Label ===" -ForegroundColor Green
    if ($total -eq 0) {
        Write-Host "  未发现影子日志（当前模式可能是 OFF；需 LIANYU_AGENT_MODE=SHADOW）" -ForegroundColor Yellow
        return
    }

    $denom = $match + $diff
    # 注意：不用 `$x = if (...) {...} else {...}` —— PowerShell 5.1 不支持 if 表达式赋值
    $rate = 0
    if ($denom -gt 0) {
        $rate = [math]::Round($diff * 100.0 / $denom, 1)
    }
    Write-Host ("  样本总数      : {0}" -f $total)
    Write-Host ("  完全一致      : {0}" -f $match)
    Write-Host ("  长度/内容不同 : {0}" -f $diff)
    Write-Host ("  两侧皆空      : {0}" -f $both)
    Write-Host ("  漂移率        : {0}%" -f $rate) -ForegroundColor $(if ($rate -le 30) { 'Green' } elseif ($rate -le 70) { 'Yellow' } else { 'Red' })
}

Get-Stats -Label "桌面感知（/api/desktop/observe）" `
    -Pattern 'Shadow observe\[' -MatchWord 'match \(' -DiffWord 'DIFF java='

Get-Stats -Label "主动发言族（PROACTIVE / COLD_OPEN / CITY_CHANGE）" `
    -Pattern 'Proactive shadow\[' -MatchWord 'match \(' -DiffWord 'DIFF java='

if ($Samples -gt 0) {
    Write-Host ""
    Write-Host "=== 最近 $Samples 条 DIFF 样例 ===" -ForegroundColor Green
    $diffLines = $lines | Where-Object { $_ -match 'DIFF java=' } | Select-Object -Last $Samples
    if (-not $diffLines) {
        Write-Host "  无 DIFF（要么没开 SHADOW，要么完全一致）"
    } else {
        $diffLines | ForEach-Object { Write-Host "  $_" }
    }
    Write-Host ""
    Write-Host "提示：DIFF 只比较长度与空值，不看语义。要定位差异需同时开启 Python 侧日志：" -ForegroundColor DarkGray
    Write-Host "      docker logs lianyu-agent --tail 500" -ForegroundColor DarkGray
}
