<#
.SYNOPSIS
    检查 Java 侧是否还残留 AI 逻辑（目标：Java 零 AI 逻辑）。

.DESCRIPTION
    终局形态：Java 只保留三条基础设施 seam —— 加解密、长连接会话状态（桌面桥接）、
    事务/落库。其余 AI 逻辑（prompt 文本、模型调用、记忆处理）必须只在 Python。

    本脚本给出**可执行的门禁**，而不是靠人工 review：
      1. prompt 文本痕迹（中文提示词、提示词段落标记）
      2. 模型调用点（AiChatService / Spring AI ChatModel / 直接 HTTP 调模型）
      3. 已知 AI 类清单（迁移完成后应逐个消失）

    退出码：0 = 无残留；1 = 有残留（可用于 CI gate）。

.PARAMETER Mode
    report  = 只打印清单（默认）
    gate    = 有残留即失败（CI 用）
    strict  = 连「已知 AI 类」也必须消失（OFF 退役后使用）

.EXAMPLE
    pwsh scripts/check-java-ai-free.ps1
    pwsh scripts/check-java-ai-free.ps1 -Mode gate
#>
param(
    [ValidateSet('report', 'gate', 'strict')]
    [string]$Mode = 'report'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $repoRoot 'backend'

if (-not (Test-Path $javaRoot)) {
    Write-Error "找不到 Java 源码目录: $javaRoot"
}

# 只扫生产代码，测试与 fixture 不算
$files = Get-ChildItem -Path $javaRoot -Recurse -Include *.java -File |
    Where-Object { $_.FullName -notmatch '\\test\\' }

# ── 允许保留的三条 seam（它们不是 AI 逻辑）──
$allowedFiles = @(
    'ApiKeyVaultService.java',        # 加解密
    'JasyptUtil.java',
    'AgentBridgeService.java',        # 长连接会话状态
    'AgentBridgeController.java',
    'ClientBridgeToolCallback.java'
)

# ── 规则 1：prompt 文本痕迹 ──
$promptMarkers = @(
    '=== 关于用户的记忆',
    '=== 当前情绪状态',
    '=== 安全与边界',
    '=== 可用工具',
    '回复规则：',
    '表达要求：',
    '当前心情：',
    '状态签名：',
    '上一刻心情：',
    '你是「',            # 常见人设开头
    '{{MENTION}}',       # 模板占位符
    '{{NAME}}'
)

# ── 规则 2：模型调用点 ──
$modelCallPatterns = @(
    'aiChatService\.chat',
    '\.chatBlocking\(',
    '\.chatStream\(',
    'OpenAiChatModel',
    'OllamaChatModel',
    'ChatClient\.builder',
    'springframework\.ai\.chat\.model'
)

# ── 规则 3：已知 AI 类（迁移完成后应为空）──
$knownAiClasses = @(
    'ChatTurnPromptAssembler.java',
    'CharacterPromptBuilder.java',
    'MemoryRetriever.java',
    'RelationshipStateService.java',
    'CharacterStateService.java',
    'SessionSummaryService.java',
    'SessionSummaryMerger.java',
    'MemoryLlmExtractor.java',
    'ToolManager.java',
    'PromptsRuleEngine.java',
    'LanguageGateService.java'
)

$promptHits = New-Object System.Collections.Generic.List[object]
$modelHits = New-Object System.Collections.Generic.List[object]

foreach ($file in $files) {
    if ($allowedFiles -contains $file.Name) { continue }

    $lines = [System.IO.File]::ReadAllLines($file.FullName, [Text.Encoding]::UTF8)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        foreach ($marker in $promptMarkers) {
            if ($line.Contains($marker)) {
                $promptHits.Add([pscustomobject]@{
                    File = $file.FullName.Replace("$repoRoot\", '')
                    Line = $i + 1
                    Marker = $marker
                    Text = $line.Trim()
                })
                break
            }
        }
        foreach ($pattern in $modelCallPatterns) {
            if ($line -match $pattern) {
                $modelHits.Add([pscustomobject]@{
                    File = $file.FullName.Replace("$repoRoot\", '')
                    Line = $i + 1
                    Pattern = $pattern
                    Text = $line.Trim()
                })
                break
            }
        }
    }
}

$classHits = $files | Where-Object { $knownAiClasses -contains $_.Name } | ForEach-Object {
    [pscustomobject]@{
        File = $_.FullName.Replace("$repoRoot\", '')
        Lines = ([System.IO.File]::ReadAllLines($_.FullName)).Count
    }
}

# ── 输出 ──
Write-Host ''
Write-Host '===== Java AI 逻辑残留清单 =====' -ForegroundColor Cyan

Write-Host "`n[1] prompt 文本痕迹: $($promptHits.Count) 处" -ForegroundColor Yellow
$promptHits | Group-Object File | Sort-Object Count -Descending | Select-Object -First 15 | ForEach-Object {
    Write-Host ("  {0,4} 处  {1}" -f $_.Count, $_.Name)
}

Write-Host "`n[2] 模型调用点: $($modelHits.Count) 处" -ForegroundColor Yellow
$modelHits | Group-Object File | Sort-Object Count -Descending | Select-Object -First 15 | ForEach-Object {
    Write-Host ("  {0,4} 处  {1}" -f $_.Count, $_.Name)
}

Write-Host "`n[3] 已知 AI 类: $($classHits.Count) 个" -ForegroundColor Yellow
$classHits | Sort-Object File | ForEach-Object {
    Write-Host ("  {0,6} 行  {1}" -f $_.Lines, $_.File)
}

Write-Host "`n[4] 允许保留的 seam（不计入违规）: $($allowedFiles.Count) 个" -ForegroundColor DarkGray
$allowedFiles | ForEach-Object { Write-Host "  $_" }

# 判定
$gateViolations = $promptHits.Count + $modelHits.Count
$strictViolations = $gateViolations + $classHits.Count

Write-Host ''
if ($Mode -eq 'report') {
    Write-Host "报告模式：prompt 文本 $($promptHits.Count) / 模型调用 $($modelHits.Count) / 已知 AI 类 $($classHits.Count)" -ForegroundColor Cyan
    exit 0
}
if ($Mode -eq 'gate') {
    if ($gateViolations -gt 0) {
        Write-Host "GATE 失败：Java 仍含 $gateViolations 处 AI 逻辑（prompt 文本或模型调用）" -ForegroundColor Red
        exit 1
    }
    Write-Host 'GATE 通过：Java 无 prompt 文本、无模型调用' -ForegroundColor Green
    exit 0
}
if ($Mode -eq 'strict') {
    if ($strictViolations -gt 0) {
        Write-Host "STRICT 失败：仍有 $strictViolations 项（含 $($classHits.Count) 个已知 AI 类）" -ForegroundColor Red
        exit 1
    }
    Write-Host 'STRICT 通过：Java 已无任何 AI 逻辑残留' -ForegroundColor Green
    exit 0
}
