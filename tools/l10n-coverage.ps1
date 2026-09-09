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
    [int]$Top = 25,
    # 不翻译的路径（方案：LLM 提示词 / 角色人设 / 词典自身 / 提示词生成器）。
    # 需要审计 llm/ 里的诊断层文案时传更宽松的正则，例如：-SkipPathRegex '([\\/]config[\\/]|[\\/]i18n[\\/]|Prompt)'
    [string]$SkipPathRegex = '(?i)([\\/]llm[\\/]|[\\/]config[\\/]|[\\/]i18n[\\/]|Prompt)'
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
                if ($ch -eq '\') {
                    # 保留转义原样（\n / \" / \$），报告里的字符串与 Kotlin 源码字面量一致，可直接当词典 key
                    if (($i + 1) -lt $n) { [void]$sb.Append($ch); [void]$sb.Append($Text[$i + 1]) }
                    $i += 2
                    continue
                }
                if ($ch -eq '"') { break }
                # ${...} 模板表达式：整体吃掉（含内部嵌套字符串/花括号），否则内部引号会被误当字符串结尾
                if ($ch -eq '$' -and ($i + 1) -lt $n -and $Text[$i + 1] -eq '{') {
                    [void]$sb.Append('${')
                    $i += 2
                    $depth = 1
                    while ($i -lt $n -and $depth -gt 0) {
                        $c2 = $Text[$i]
                        if ($c2 -eq '{') { $depth++ }
                        elseif ($c2 -eq '}') { $depth-- }
                        elseif ($c2 -eq '"') {
                            [void]$sb.Append($c2)
                            $i++
                            while ($i -lt $n -and $Text[$i] -ne '"') {
                                if ($Text[$i] -eq '\') {
                                    [void]$sb.Append($Text[$i])
                                    $i++
                                    if ($i -lt $n) { [void]$sb.Append($Text[$i]); $i++ }
                                    continue
                                }
                                if ($Text[$i] -eq "`n") { $line++ }
                                [void]$sb.Append($Text[$i])
                                $i++
                            }
                            if ($i -lt $n) { [void]$sb.Append('"'); $i++ }
                            continue
                        }
                        if ($c2 -eq "`n") { $line++ }
                        [void]$sb.Append($c2)
                        $i++
                    }
                    if ($i -lt $n -and $Text[$i] -eq '}') { [void]$sb.Append('}'); $i++ }
                    continue
                }
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
                while ($k -ge 0 -and ([char]::IsLetterOrDigit($Text[$k]) -or $Text[$k] -eq '_' -or $Text[$k] -eq '.')) { $k-- }
                if ($end -ge ($k + 1)) { $prefix = $Text.Substring($k + 1, $end - $k) }
            }

            $out.Add([pscustomobject]@{ Line = $startLine; Pos = $start; Value = $sb.ToString(); Prefix = $prefix })
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
    foreach ($one in ($d -split ',')) {
        $p = Join-Path $SrcRoot $one.Trim()
        if (Test-Path -LiteralPath $p) {
            Get-ChildItem -LiteralPath $p -Recurse -File -Filter *.kt
        }
    }
}
$files = $files | Where-Object { $_.FullName -notmatch $SkipPathRegex } | Sort-Object FullName -Unique

# 词典已收录的 key（中文原文）：数据驱动内容（如 ui/guide 的内容层）在渲染处包装，
# 源码字面量本身不会被 t() 包住，用「是否已进词典」衡量这部分进度。
$dictKeys = New-Object 'System.Collections.Generic.HashSet[string]'
$dictDir = Join-Path $SrcRoot 'i18n\dict'
if (Test-Path -LiteralPath $dictDir) {
    foreach ($df in (Get-ChildItem -LiteralPath $dictDir -File -Filter 'En*.kt')) {
        $dtext = [System.IO.File]::ReadAllText($df.FullName, [System.Text.Encoding]::UTF8)
        foreach ($m in [regex]::Matches($dtext, '(?m)^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"')) {
            [void]$dictKeys.Add($m.Groups[1].Value)
        }
    }
}

$rows = [System.Collections.Generic.List[object]]::new()
$totalZh = 0
$wrappedZh = 0
$inDictZh = 0
$logZh = 0
$ignoredZh = 0
# 调试日志（Log.d/w/i/e、CrashCapture.logEvent）按方案不翻译：不计入分母
$LogCallRegex = '(?s)(Log\.[dwiev]\(|CrashCapture\.log\w*\(|\.logEvent\()[^)]*$'
# 认定「已包装」的调用前缀（严格白名单，避免把 String.format("中文…") 误判成已翻译）
$WrappedPrefixes = @('t', 'tf', 'L10n.t', 'L10n.format', 'L10nRuntime.t', 'L10nRuntime.format')

foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $fileLines = $text -split "`r?`n"
    foreach ($lit in (Get-KotlinStringLiterals -Text $text)) {
        if ($lit.Value -notmatch $CjkRegex) { continue }
        $ctxStart = [Math]::Max(0, $lit.Pos - 120)
        $ctx = $text.Substring($ctxStart, $lit.Pos - $ctxStart)
        $isLog = $ctx -match $LogCallRegex
        if ($isLog) { $logZh++; continue }
        # 显式豁免：源码行含 `l10n:ignore`（如 TTS 文本规范化、协议常量等非界面文案）
        $srcLine = if ($lit.Line -ge 1 -and $lit.Line -le $fileLines.Count) { $fileLines[$lit.Line - 1] } else { '' }
        if ($srcLine -match 'l10n:ignore') { $ignoredZh++; continue }
        $totalZh++
        $isWrapped = $WrappedPrefixes -contains $lit.Prefix
        if ($isWrapped) { $wrappedZh++ }
        $inDict = $dictKeys.Contains($lit.Value)
        if ($inDict) { $inDictZh++ }
        $rows.Add([pscustomobject]@{
            File    = $f.FullName.Substring($SrcRoot.Length).TrimStart('\', '/')
            Line    = $lit.Line
            Wrapped = $isWrapped
            InDict  = $inDict
            Value   = $lit.Value
        })
    }
}

$pending = @($rows | Where-Object { -not $_.Wrapped -and -not $_.InDict })
# 已包 t()/tf() 但词典里没有这条 key —— 切到英/日会静默回退中文，必须补齐（每批 QA 门禁）
$missingDict = @($rows | Where-Object { $_.Wrapped -and -not $_.InDict })
$covered = @($rows | Where-Object { $_.Wrapped -or $_.InDict })
# 词典有这条 key、但源码里这句中文没被 t()/tf() 包住（数据驱动内容在渲染处包属正常；否则是漏包）
$unwrappedInDict = @($rows | Where-Object { $_.InDict -and -not $_.Wrapped })
$coverage = if ($totalZh -eq 0) { 100 } else { [math]::Round(100.0 * $covered.Count / $totalZh, 1) }
$wrapRate = if ($totalZh -eq 0) { 100 } else { [math]::Round(100.0 * $wrappedZh / $totalZh, 1) }

Write-Output "扫描根目录 : $SrcRoot"
Write-Output "扫描文件数 : $($files.Count)"
Write-Output "中文字面量 : $totalZh  (已包装 $wrappedZh / 已进词典 $inDictZh / 待处理 $($pending.Count)；另有 $logZh 条调试日志、$ignoredZh 条 l10n:ignore 豁免，均不翻译)"
Write-Output "包装率     : $wrapRate%   词典覆盖度（包装 + 已进词典）: $coverage%"
if ($missingDict.Count -gt 0) {
    Write-Output "已包装但缺词条 : $($missingDict.Count) 条（切到英/日会回退中文，需补词典）"
}
if ($unwrappedInDict.Count -gt 0) {
    Write-Output "已进词典但未包装 : $($unwrappedInDict.Count) 条（数据驱动内容可忽略，其余是漏包）"
}
Write-Output ''

if ($List -and $missingDict.Count -gt 0) {
    Write-Output '--- 已包装但缺词条 ---'
    $missingDict | Sort-Object File, Line | ForEach-Object { '{0}:{1}  {2}' -f $_.File, $_.Line, $_.Value }
    Write-Output ''
}

if ($List -and $unwrappedInDict.Count -gt 0) {
    Write-Output '--- 已进词典但未包装 ---'
    $unwrappedInDict | Sort-Object File, Line | ForEach-Object { '{0}:{1}  {2}' -f $_.File, $_.Line, $_.Value }
    Write-Output ''
}

Write-Output '--- 按目录（待处理条数）---'
$pending |
    Group-Object { ($_.File -split '[\\/]')[0..1] -join '\' } |
    Sort-Object Count -Descending |
    ForEach-Object { '{0,6}  {1}' -f $_.Count, $_.Name }
Write-Output ''

Write-Output "--- 待处理最多的文件（前 $Top）---"
$byFile = $pending | Group-Object File | Sort-Object Count -Descending
if ($Top -gt 0) { $byFile = $byFile | Select-Object -First $Top }
$byFile | ForEach-Object { '{0,6}  {1}' -f $_.Count, $_.Name }

if ($List) {
    Write-Output ''
    Write-Output '--- 待处理明细 ---'
    $pending | Sort-Object File, Line | ForEach-Object {
        '{0}:{1}  {2}' -f $_.File, $_.Line, $_.Value
    }
}
