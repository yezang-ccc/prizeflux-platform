<#
    PrizeFlux 身份切片：独立进程 HTTP 冒烟。

    前置条件（本脚本只做 HTTP 探测与业务断言，不启动进程、不建库）：
    1) MySQL 已启动：docker compose -f deploy/compose.foundation.yml up -d
    2) 应用已独立启动（非 MockMvc/Testcontainers），并在首次启动完成账号初始化：
       $env:PFX_JWT_SECRET                 = '至少 32 字节的随机串'
       $env:PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED = 'true'   # 首次创建运营账号，成功后移除
       $env:PFX_BOOTSTRAP_OPERATOR_USERNAME = 'operator'
       $env:PFX_BOOTSTRAP_OPERATOR_PASSWORD = '...'
       $env:PRIZEFLUX_BOOTSTRAP_DEMO_USER_ENABLED = 'true'  # 幂等，可保留
       $env:PFX_BOOTSTRAP_DEMO_USERNAME    = 'demo-user'
       $env:PFX_BOOTSTRAP_DEMO_PASSWORD    = '...'
       cd backend
       .\mvnw.cmd -pl prizeflux-campaign spring-boot:run -Dspring-boot.run.profiles=local-full

    验证覆盖：健康接口公开、匿名 401、运营登录/建活动/配置/发布、用户登录/抽奖、
    同键重放一致、同键异活动 409、用户越权 403、/me 服务端身份。
    不打印口令或令牌正文；退出码 0=全通过，1=有失败。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$Operator = 'operator',
    [string]$OperatorPassword = $env:PFX_BOOTSTRAP_OPERATOR_PASSWORD,
    [string]$DemoUser = 'demo-user',
    [string]$DemoUserPassword = $env:PFX_BOOTSTRAP_DEMO_PASSWORD
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OperatorPassword) -or [string]::IsNullOrWhiteSpace($DemoUserPassword)) {
    throw '缺少账号口令：请通过 -OperatorPassword/-DemoUserPassword 或对应 PFX_ 环境变量提供。'
}

$script:failures = 0

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Headers = @{},
        [string]$Body = $null
    )
    $params = @{
        Uri               = "$BaseUrl$Path"
        Method            = $Method
        Headers           = $Headers
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = $Body
    }
    $resp = Invoke-WebRequest @params
    $parsed = $null
    if ($resp.Content) {
        try { $parsed = $resp.Content | ConvertFrom-Json } catch { $parsed = $null }
    }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $parsed }
}

function Assert-Status {
    param([string]$Name, [int]$Expected, $Actual)
    if ($Expected -eq $Actual.Status) {
        Write-Host "[PASS] $Name" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Name — 期望 $Expected，实际 $($Actual.Status)" -ForegroundColor Red
        $script:failures++
    }
}

# 每次运行用唯一活动 ID 与幂等键，避免与上次运行的状态冲突（幂等键按 userId+requestKey 查找）。
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$campaignId = [long]$stamp
$campaignId2 = $campaignId + 1
$requestKey = "smoke-$stamp"

# 1) 健康接口公开
$health = Invoke-Api GET '/api/health'
Assert-Status '健康接口公开' 200 $health

# 2) 匿名写入口 -> 401
$anonBody = '{"id":1,"name":"anon","startsAt":"2026-01-01T00:00:00Z","endsAt":"2026-01-02T00:00:00Z"}'
$anon = Invoke-Api POST '/api/campaigns' @{} $anonBody
Assert-Status '匿名创建活动 401' 401 $anon

# 3) 运营登录
$opLoginBody = (@{ username = $Operator; password = $OperatorPassword } | ConvertTo-Json -Compress)
$opLogin = Invoke-Api POST '/api/auth/login' @{} $opLoginBody
Assert-Status '运营登录 200' 200 $opLogin
$opToken = $null
if ($opLogin.Body -and $opLogin.Body.data) { $opToken = $opLogin.Body.data.token }
if ([string]::IsNullOrWhiteSpace($opToken)) {
    Write-Host '[FAIL] 运营登录未返回令牌' -ForegroundColor Red
    $script:failures++
}
$opHeaders = @{ Authorization = "Bearer $opToken" }

# 4) 运营创建活动（时间窗覆盖当前时刻）
$now = [DateTime]::UtcNow
$startsAt = $now.AddHours(-1).ToString('o')
$endsAt = $now.AddHours(1).ToString('o')
$createBody = (@{ id = $campaignId; name = 'smoke-campaign'; startsAt = $startsAt; endsAt = $endsAt } | ConvertTo-Json -Compress)
$create = Invoke-Api POST '/api/campaigns' $opHeaders $createBody
Assert-Status '运营创建活动 201' 201 $create

# 5) 运营配置奖品（概率 1.0 且库存 1，保证单次必中）
$cfgBody = (@{ dailyLimit = 5; prizes = @(@{ id = 1; name = 'smoke-prize'; totalStock = 1; probability = 1.0 }) } | ConvertTo-Json -Compress -Depth 5)
$configure = Invoke-Api POST "/api/campaigns/$campaignId/configuration" $opHeaders $cfgBody
Assert-Status '运营配置奖品 200' 200 $configure

# 6) 运营发布
$publish = Invoke-Api POST "/api/campaigns/$campaignId/publish" $opHeaders
Assert-Status '运营发布 200' 200 $publish

# 7) 演示用户登录
$userLoginBody = (@{ username = $DemoUser; password = $DemoUserPassword } | ConvertTo-Json -Compress)
$userLogin = Invoke-Api POST '/api/auth/login' @{} $userLoginBody
Assert-Status '用户登录 200' 200 $userLogin
$userToken = $null
if ($userLogin.Body -and $userLogin.Body.data) { $userToken = $userLogin.Body.data.token }
if ([string]::IsNullOrWhiteSpace($userToken)) {
    Write-Host '[FAIL] 用户登录未返回令牌' -ForegroundColor Red
    $script:failures++
}
$userHeaders = @{ Authorization = "Bearer $userToken" }

# 8) 用户抽奖 -> 200 且中奖
$drawBody = (@{ campaignId = $campaignId; requestKey = $requestKey } | ConvertTo-Json -Compress)
$draw = Invoke-Api POST '/api/draws' $userHeaders $drawBody
Assert-Status '用户抽奖 200' 200 $draw
$outcome = $null; $requestId = $null
if ($draw.Body -and $draw.Body.data) { $outcome = $draw.Body.data.outcome; $requestId = $draw.Body.data.requestId }
if ($outcome -eq 'WON') {
    Write-Host '[PASS] 抽奖结果中奖（WON）' -ForegroundColor Green
} else {
    Write-Host "[FAIL] 抽奖结果非中奖：$outcome" -ForegroundColor Red
    $script:failures++
}

# 9) 同键同参数重放 -> 200 且结果一致
$replay = Invoke-Api POST '/api/draws' $userHeaders $drawBody
Assert-Status '同键重放 200' 200 $replay
if ($replay.Body -and $replay.Body.data -and $replay.Body.data.requestId -eq $requestId -and $replay.Body.data.outcome -eq $outcome) {
    Write-Host '[PASS] 同键重放结果一致' -ForegroundColor Green
} else {
    Write-Host '[FAIL] 同键重放结果不一致' -ForegroundColor Red
    $script:failures++
}

# 10) 同键异活动 -> 409（重放校验先于活动配置校验，第二个活动无需存在）
$diffBody = (@{ campaignId = $campaignId2; requestKey = $requestKey } | ConvertTo-Json -Compress)
$diff = Invoke-Api POST '/api/draws' $userHeaders $diffBody
Assert-Status '同键异活动 409' 409 $diff

# 11) 用户越权创建活动 -> 403
$userCreate = Invoke-Api POST '/api/campaigns' $userHeaders $createBody
Assert-Status '用户越权创建 403' 403 $userCreate

# 12) /me 返回服务端身份（不信任任何身份头）
$me = Invoke-Api GET '/api/auth/me' $userHeaders
Assert-Status '用户 /me 200' 200 $me
if ($me.Body -and $me.Body.data -and $me.Body.data.username -eq $DemoUser) {
    Write-Host '[PASS] /me 返回服务端身份' -ForegroundColor Green
} else {
    Write-Host '[FAIL] /me 身份不符' -ForegroundColor Red
    $script:failures++
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host '冒烟通过：全部断言成功。' -ForegroundColor Green
    exit 0
} else {
    Write-Host "冒烟未通过：$($script:failures) 项断言失败。" -ForegroundColor Red
    exit 1
}
