<#
    PrizeFlux 本地环境检查程序。

    这个脚本只读取工具版本，不安装或修改任何软件。
    返回码为 0 表示必需环境完整；返回码为 1 表示至少缺少一项。
#>
param()

$ErrorActionPreference = 'Continue'
$results = [System.Collections.Generic.List[object]]::new()

function Add-ToolCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @('--version'),
        [bool]$Required = $true,
        [string]$MissingHint = ''
    )

    $resolvedCommand = Get-Command -Name $Command -ErrorAction SilentlyContinue
    if ($null -eq $resolvedCommand) {
        $results.Add([pscustomobject]@{
            Name = $Name
            Required = $Required
            Ready = $false
            Detail = '当前终端的 PATH 中未找到命令'
            Hint = $MissingHint
        })
        return
    }

    try {
        # 很多版本命令会把结果写到错误输出，因此这里把两种输出合并读取。
        $versionOutput = @(& $Command @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $firstLine = [string]($versionOutput | Select-Object -First 1)

        # 原生命令即使失败也不一定抛出 PowerShell 异常，因此还要检查退出码。
        if ($exitCode -ne 0) {
            $results.Add([pscustomobject]@{
                Name = $Name
                Required = $Required
                Ready = $false
                Detail = "命令执行失败，退出码 $exitCode：$firstLine"
                Hint = $MissingHint
            })
            return
        }

        $results.Add([pscustomobject]@{
            Name = $Name
            Required = $Required
            Ready = $true
            Detail = $firstLine
            Hint = ''
        })
    }
    catch {
        $results.Add([pscustomobject]@{
            Name = $Name
            Required = $Required
            Ready = $false
            Detail = $_.Exception.Message
            Hint = $MissingHint
        })
    }
}

Add-ToolCheck -Name 'Java' -Command 'java' -Arguments @('-version') -MissingHint '需要 Java 17 JDK。安装或切换 JDK 后，请重新打开终端；本脚本不会自动修改 JAVA_HOME。'
Add-ToolCheck -Name 'Maven' -Command 'mvn' -Arguments @('-version') -MissingHint '可以检查全局 Maven 是否存在，但 PrizeFlux 构建必须优先使用 backend\mvnw.cmd。'
Add-ToolCheck -Name 'Node.js' -Command 'node' -MissingHint '后续 Vue 前端需要 Node.js；安装后请重新打开终端再检查。'
Add-ToolCheck -Name 'npm' -Command 'npm' -MissingHint 'npm 通常随 Node.js 安装；请先确认 Node.js 安装状态。'
Add-ToolCheck -Name 'Git' -Command 'git' -MissingHint 'Git 用于记录可追溯提交；安装后请重新打开终端再检查。'
Add-ToolCheck -Name 'Docker' -Command 'docker' -MissingHint '当前 Windows 终端无法调用 docker。请确认 Docker Desktop 已安装并启动，或在已配置 Docker CLI 的终端重试；本脚本不会安装 Docker 或修改 PATH。'

Write-Host ''
Write-Host 'PrizeFlux 本地环境检查' -ForegroundColor Cyan
Write-Host '========================'

foreach ($result in $results) {
    $status = if ($result.Ready) { '就绪' } else { '缺失' }
    $color = if ($result.Ready) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1,-10} {2}" -f $status, $result.Name, $result.Detail) -ForegroundColor $color
    if (-not $result.Ready -and -not [string]::IsNullOrWhiteSpace($result.Hint)) {
        Write-Host ("         下一步：{0}" -f $result.Hint) -ForegroundColor Yellow
    }
}

$missingRequired = @($results | Where-Object { $_.Required -and -not $_.Ready })
Write-Host ''

if ($missingRequired.Count -gt 0) {
    Write-Host ("检查未通过：缺少 {0} 项必需环境。" -f $missingRequired.Count) -ForegroundColor Red
    exit 1
}

Write-Host '检查通过：当前阶段需要的命令均可使用。' -ForegroundColor Green
exit 0
