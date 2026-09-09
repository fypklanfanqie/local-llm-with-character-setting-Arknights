#Requires -Version 5.1
<#
.SYNOPSIS
    多语言覆盖度脚本：扫描 Kotlin 源码里「未被 t()/tf() 包装的中文字面量」。

.DESCRIPTION
    以「中文原文为 key」的中央词典方案下，翻译进度 = 被包装的中文字面量占比。
    本脚本用一个小状态机剥离注释/原始字符串后，找出所有含中文的字符串字面量，
    并判断它前面是否紧跟 `t(` 或 `tf(`（即已进词典查表）。

    默认扫描 UI 面（ui/work/notification/util/provider/conversationexport/manager 等），
    自动排除 llm/（提示词）、config/（角色人设）、i18n/（词典本身）与文件名含 Prompt 的文件
    —— 这些按方案**不翻译**，AI 输出语言与界面语言独立。

.PARAMETER SrcRoot
    源码根目录，默认 <repo>/app/src/main/java/com/rhodesisland/terminal。

.PARAMETER Dir
    只扫描这些子目录（默认见 $DefaultDirs）。

.PARAMETER List
    逐条打印未包装的中文字面量（file:line 原文）。

.PARAMETER Top
    按文件打印未包装数量最多的前 N 个文件（默认 25，0 = 全部）。

.EXAMPLE
    powershell -File tools/l10n-coverage.ps1
    powershell -File tools/l10n-coverage.ps1 -List -Top 0
    powershell -File tools/l10n-coverage.ps1 -Dir ui\settings,ui\chat
#>
[CmdletBinding()]
param(
    [string]$SrcRoot,
    [string[]]$Dir,
    [switch]$List,
    [int]$Top = 25
)

$ErrorActionPreference = 'Stop'

if (-not $SrcRoot) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $SrcRoot = Join-Path $scriptDir '..\app\src\main\java\com\rhodesisland\terminal'
}

$DefaultDirs = @(
    'ui', 'work', 'notification', 'util', 'provider', 'conversationexport', 'manager',
    'tts', 'service', 'perfmon', 'data'
)
# 不翻译的路径（方案：LLM 提示词 / 角色人设 / 词典自身 / 提示词生成器）
$SkipPathRegex = '(?i)([\\/]llm[\\/]|[\\/]config[\\/]|[\\/]i18n[\\/]|Prompt)'
$CjkRegex = '[\u4e00-\u9fff\u3400-\u4dbf\u3040-\u30ff]'

function Get-KotlinStringLiterals {
    param([string]$Text)

    $out = [System.Collections.Generic.List[object]]::new()
    $i = 0
    $n = $Text.Length
    $line = 1

    while ($i -lt $n) {
        $c = $Text[$i]

        if ($c -eq "`n") { $line++; $i++; continue }

        # 行注释
        if ($c -eq '/' -and ($i + 1) -lt $n -and $Text[$i + 1] -eq '/') {
            while ($i -lt $n -and $Text[$i] -ne "`n") { $i++ }
            continue
        }
        # 块注释
        if ($c -eq '/' -and ($i + 1) -lt $n -and $Text[$i + 1] -eq '*') {
            $i += 2
            while (($i + 1) -lt $n -and -not ($Text[$i] -eq '*' -and $Text[$i + 1] -eq '/')) {
                if ($Text[$i] -eq "`n") { $line++ }
                $i++
            }
            $i += 2
            continue
        }
        # 原始字符串 """..."""
        if ($c -eq '"' -and ($i + 2) -lt $n -and $Text[$i + 1] -eq '"' -and $Text[$i + 2] -eq '"') {
            $i += 3
            while (($i + 2) -lt $n -and -not ($Text[$i] -eq '"' -and $Text[$i + 1] -eq '"' -and $Text[$i + 2] -eq '"')) {
                if ($Text[$i] -eq "`n") { $line++ }
                $i++
            }
            $i += 3
            continue
        }
        # 普通字符串
        if ($c -eq '"') {
            $start = $i
            $startLine = $line
            $sb = [System.Text.StringBuilder]::new()
            $i++
            while ($i -lt $n) {
                $ch = $Text[$i]
                if ($ch -eq '\') { if (($i + 1) -lt $n) { [void]$sb.Append($Text[$i + 1]) }; $i += 2; continue }
                if ($ch -eq '"') { break }
                if ($ch -eq "`n") { $line++ }
                [void]$sb.Append($ch)
                $i++
            }
            $i++  # 跳过收尾引号

            # 前缀：紧贴左引号的标识符（t / tf 表示已包装）
            $j = $start - 1
            while ($j -ge 0 -and [char]::IsWhiteSpace($Text[$j])) { $j-- }
            $prefix = ''
            if ($j -ge 0 -and $Text[$j] -eq '(') {
                $k = $j - 1
                while ($k -ge 0 -and [char]::IsWhiteSpace($Text[$k])) { $k-- }
                $end = $k
                while ($k -ge 0 -and ([char]::IsLetterOrDigit($Text[$k]) -or $Text[$k] -eq '_')) { $k-- }
                if ($end -ge ($k + 1)) { $prefix = $Text.Substring($k + 1, $end - $k) }
            }

            $out.Add([pscustomobject]@{ Line = $startLine; Value = $sb.ToString(); Prefix = $prefix })
            continue
        }
        # 字符字面量
        if ($c -eq "'") {
            $i++
            while ($i -lt $n -and $Text[$i] -ne "'") {
                if ($Text[$i] -eq '\') { $i++ }
                if ($Text[$i] -eq "`n") { $line++ }
                $i++
            }
            $i++
            continue
        }

        $i++
    }

    return $out
}

if (-not $Dir -or $Dir.Count -eq 0) { $Dir = $DefaultDirs }

$SrcRoot = (Resolve-Path -LiteralPath $SrcRoot).Path
$files = foreach ($d in $Dir) {
    $p = Join-Path $SrcRoot $d
    if (Test-Path -LiteralPath $p) {
        Get-ChildItem -LiteralPath $p -Recurse -File -Filter *.kt
    }
}
$files = $files | Where-Object { $_.FullName -notmatch $SkipPathRegex } | Sort-Object FullName -Unique

$rows = [System.Collections.Generic.List[object]]::new()
$totalZh = 0
$wrappedZh = 0

foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($lit in (Get-KotlinStringLiterals -Text $text)) {
        if ($lit.Value -notmatch $CjkRegex) { continue }
        $totalZh++
        $isWrapped = $lit.Prefix -eq 't' -or $lit.Prefix -eq 'tf'
        if ($isWrapped) { $wrappedZh++ }
        $rows.Add([pscustomobject]@{
            File    = $f.FullName.Substring($SrcRoot.Length).TrimStart('\', '/')
            Line    = $lit.Line
            Wrapped = $isWrapped
            Value   = $lit.Value
        })
    }
}

$unwrapped = $rows | Where-Object { -not $_.Wrapped }
$coverage = if ($totalZh -eq 0) { 100 } else { [math]::Round(100.0 * $wrappedZh / $totalZh, 1) }

Write-Output "扫描根目录 : $SrcRoot"
Write-Output "扫描文件数 : $($files.Count)"
Write-Output "中文字面量 : $totalZh  (已包装 $wrappedZh / 未包装 $($unwrapped.Count))"
Write-Output "词典覆盖度 : $coverage%"
Write-Output ''

Write-Output '--- 按目录（未包装条数）---'
$unwrapped |
    Group-Object { ($_.File -split '[\\/]')[0..1] -join '\' } |
    Sort-Object Count -Descending |
    ForEach-Object { '{0,6}  {1}' -f $_.Count, $_.Name }
Write-Output ''

Write-Output "--- 未包装最多的文件（前 $Top）---"
$byFile = $unwrapped | Group-Object File | Sort-Object Count -Descending
if ($Top -gt 0) { $byFile = $byFile | Select-Object -First $Top }
$byFile | ForEach-Object { '{0,6}  {1}' -f $_.Count, $_.Name }

if ($List) {
    Write-Output ''
    Write-Output '--- 未包装明细 ---'
    $unwrapped | Sort-Object File, Line | ForEach-Object {
        '{0}:{1}  {2}' -f $_.File, $_.Line, $_.Value
    }
}
