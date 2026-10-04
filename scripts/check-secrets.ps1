<#
    PrizeFlux 本地秘密基线检查。

    扫描范围：Git 已跟踪、已暂存及未忽略的未跟踪文件，即当前可能进入提交的文本文件。
    安全边界：命中时只报告“规则类别 + 文件路径”，绝不在终端输出可能的秘密正文。
    局限：这是最小预防层，不代替专业扫描器，也不代替泄露后的撤销、轮换和历史清理。
#>
[CmdletBinding()]
param(
    # 默认从本脚本所在目录向上找到 PrizeFlux 仓库根目录。
    [string]$RepositoryPath = (Join-Path $PSScriptRoot '..')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-GitRepositoryRoot {
    param([Parameter(Mandatory = $true)][string]$Path)

    # git rev-parse 只读取仓库元数据；这里不修改暂存区、工作区或 Git 历史。
    $root = (& git -C $Path rev-parse --show-toplevel 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        throw '无法定位 Git 仓库根目录。请在 PrizeFlux 仓库内运行此脚本。'
    }
    return $root
}

$repositoryRoot = Get-GitRepositoryRoot -Path $RepositoryPath

# 每条规则都只用来判断“是否需要人工处理”，不保存或回显命中的文本。
$rules = @(
    [pscustomobject]@{
        Name = 'private-key-header'
        Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    },
    [pscustomobject]@{
        Name = 'github-token'
        Pattern = '\bgh[pousr]_[A-Za-z0-9_]{20,}\b'
    },
    [pscustomobject]@{
        Name = 'aws-access-key-id'
        Pattern = '\b(?:AKIA|ASIA)[0-9A-Z]{16}\b'
    },
    [pscustomobject]@{
        Name = 'provider-api-key'
        Pattern = '\bsk-[A-Za-z0-9_-]{20,}\b'
    },
    [pscustomobject]@{
        # 只匹配高熵形状的长字面量；Python 变量/类型声明和模板占位符不应触发。
        Name = 'likely-assigned-secret'
        # 只用水平空格，避免跨行；至少 20 字符且含数字，排除 __GENERATE...__ 等模板。
        Pattern = '(?im)^[ \t]*[A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY)[A-Z0-9_]*[ \t]*[:=][ \t]*["'']?(?!__|<|\$\{|(?:.*(?:changeme|replace|example|fake|dummy|test|your[_-]?key)))(?=[A-Za-z0-9+/=_-]{20,})(?=[A-Za-z0-9+/=_-]*[0-9])[A-Za-z0-9+/=_-]{20,}'
    }
)

$candidateFiles = @(& git -C $repositoryRoot ls-files --cached)
if ($LASTEXITCODE -ne 0) {
    throw '无法读取 Git 已跟踪文件列表。'
}
$candidateFiles += @(& git -C $repositoryRoot ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0) {
    throw '无法读取未忽略的未跟踪文件列表。'
}
$candidateFiles = @($candidateFiles | Sort-Object -Unique)

$findings = [System.Collections.Generic.List[object]]::new()
foreach ($relativePath in $candidateFiles) {
    if ([string]::IsNullOrWhiteSpace($relativePath)) {
        continue
    }

    $absolutePath = Join-Path $repositoryRoot $relativePath
    $file = [System.IO.FileInfo]::new($absolutePath)
    $file.Refresh()
    if (-not $file.Exists) {
        # Git 索引可能包含已删除但尚未提交的文件；此时没有可扫描内容，跳过即可。
        continue
    }

    # 二进制文件与过大的文件不适合按文本规则扫描；密钥文件会由文件名规则和 Git 忽略规则预防。
    if ($file.Length -gt 1MB) {
        continue
    }

    try {
        $content = [System.IO.File]::ReadAllText($absolutePath)
    }
    catch {
        # 读取失败通常意味着二进制/编码特殊文件；不输出内容，只提示路径。
        Write-Warning "无法作为文本读取：$relativePath"
        continue
    }

    foreach ($rule in $rules) {
        if ([regex]::IsMatch($content, $rule.Pattern)) {
            $findings.Add([pscustomobject]@{
                    Rule = $rule.Name
                    Path = $relativePath
                })
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host '秘密基线检查未通过：发现需要人工核实的候选内容。' -ForegroundColor Red
    foreach ($finding in $findings) {
        # 故意不输出具体行或匹配值，避免终端日志二次泄露。
        Write-Host ("[{0}] {1}" -f $finding.Rule, $finding.Path) -ForegroundColor Yellow
    }
    Write-Host '若是真实秘密：先撤销或轮换，再处理 Git 跟踪和历史；不要只删除文件。' -ForegroundColor Yellow
    exit 1
}

Write-Host "秘密基线检查通过：$($candidateFiles.Count) 个候选文件未命中最小规则集。" -ForegroundColor Green
exit 0
