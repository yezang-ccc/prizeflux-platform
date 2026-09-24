param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CampaignJar = 'backend/prizeflux-campaign/target/prizeflux-campaign-0.1.0-SNAPSHOT.jar',
    [string]$AiJar = 'backend/prizeflux-ai/target/prizeflux-ai-0.1.0-SNAPSHOT.jar'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'Set JAVA_HOME to a JDK 17 installation before running this smoke test.'
}
$java = Join-Path $JavaHome 'bin/java.exe'
$campaignJarPath = (Resolve-Path (Join-Path $repoRoot $CampaignJar)).Path
$aiJarPath = (Resolve-Path (Join-Path $repoRoot $AiJar)).Path
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JAVA_HOME does not contain bin/java.exe.' }

# Do not attach to or change an existing local service: this smoke test needs its own ports.
$requiredPorts = @(13306, 8081, 8082)
$busyPorts = foreach ($port in $requiredPorts) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { $port }
}
if ($busyPorts) { throw "Required smoke-test port(s) already in use: $($busyPorts -join ', '). Stop the local service or retry later." }

$tag = [guid]::NewGuid().ToString('N').Substring(0, 10)
$containerName = "pfx-ai-campaign-$tag"
$logBase = Join-Path ([IO.Path]::GetTempPath()) "pfx-ai-campaign-$tag"
$bootstrapStdout = "$logBase-bootstrap.out.log"
$bootstrapStderr = "$logBase-bootstrap.err.log"
$campaignStdout = "$logBase-campaign.out.log"
$campaignStderr = "$logBase-campaign.err.log"
$aiStdout = "$logBase-ai.out.log"
$aiStderr = "$logBase-ai.err.log"
$processIds = [System.Collections.Generic.List[int]]::new()
$containerStarted = $false
$token = $null

$environmentNames = @(
    'MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD',
    'PFX_DB_URL', 'PFX_DB_USER', 'PFX_DB_PASSWORD', 'PFX_JWT_SECRET',
    'PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED', 'PFX_BOOTSTRAP_OPERATOR_USERNAME',
    'PFX_BOOTSTRAP_OPERATOR_PASSWORD', 'PFX_CAMPAIGN_BASE_URL'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function New-Secret([int]$bytes = 32) {
    [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes($bytes))
}

function Start-ServiceProcess([string]$jar, [string]$stdout, [string]$stderr, [string[]]$arguments) {
    $process = Start-Process -FilePath $java -ArgumentList (@('-jar', $jar) + $arguments) `
        -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $processIds.Add($process.Id)
    return $process
}

function Wait-HttpHealth([string]$uri, [System.Diagnostics.Process]$process, [string[]]$logPaths,
    [int]$timeoutSeconds = 100) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) {
            $diagnostic = @()
            foreach ($path in $logPaths) {
                if (Test-Path -LiteralPath $path -PathType Leaf) {
                    $diagnostic += Get-Content -LiteralPath $path -Tail 100
                    $matches = Select-String -LiteralPath $path `
                        -Pattern 'ERROR|Caused by:|Exception|APPLICATION FAILED|Could not resolve|Could not load|Failed to' `
                        | Select-Object -Last 15 | ForEach-Object { $_.Line }
                    if ($matches) { $diagnostic += $matches }
                }
            }
            foreach ($secret in @($rootPassword, $dbPassword, $jwtSecret, $operatorPassword, $token)) {
                if (-not [string]::IsNullOrEmpty($secret)) {
                    $diagnostic = @($diagnostic | ForEach-Object { $_.Replace($secret, '[REDACTED]') })
                }
            }
            $excerpt = ($diagnostic -join [Environment]::NewLine)
            throw "A service exited before its health endpoint became ready (exit $($process.ExitCode)).`n$excerpt"
        }
        try { return Invoke-RestMethod -Uri $uri -TimeoutSec 2 }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw 'Timed out waiting for a local service health endpoint.'
}

try {
    $rootPassword = New-Secret
    $dbPassword = New-Secret
    $jwtSecret = New-Secret
    $operatorPassword = New-Secret
    $operatorUsername = "op$tag"

    # MYSQL_* values are forwarded from this process, not included in Docker CLI arguments.
    $env:MYSQL_ROOT_PASSWORD = $rootPassword
    $env:MYSQL_DATABASE = 'prizeflux'
    $env:MYSQL_USER = 'prizeflux'
    $env:MYSQL_PASSWORD = $dbPassword
    & docker run --rm --detach --name $containerName --publish '127.0.0.1:13306:3306' `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD mysql:8.4 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable MySQL 8.4 container.' }
    $containerStarted = $true
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }

    $dbDeadline = [DateTime]::UtcNow.AddSeconds(120)
    $databaseReady = $false
    while ([DateTime]::UtcNow -lt $dbDeadline) {
        & docker exec $containerName mysqladmin ping -h 127.0.0.1 --silent *> $null
        if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $databaseReady) { throw 'The disposable MySQL container did not become ready.' }

    $env:PFX_DB_URL = 'jdbc:mysql://127.0.0.1:13306/prizeflux'
    $env:PFX_DB_USER = 'prizeflux'
    $env:PFX_DB_PASSWORD = $dbPassword
    $env:PFX_JWT_SECRET = $jwtSecret
    $env:PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED = 'true'
    $env:PFX_BOOTSTRAP_OPERATOR_USERNAME = $operatorUsername
    $env:PFX_BOOTSTRAP_OPERATOR_PASSWORD = $operatorPassword

    $campaignArgs = @(
        '--spring.profiles.active=local-full',
        '--prizeflux.kafka.enabled=false',
        '--prizeflux.outbox.relay.enabled=false',
        '--prizeflux.traffic.draw-rate-limit.enabled=false'
    )
    $bootstrapProcess = Start-ServiceProcess $campaignJarPath $bootstrapStdout $bootstrapStderr $campaignArgs
    $null = Wait-HttpHealth 'http://127.0.0.1:8081/api/health' $bootstrapProcess @($bootstrapStdout, $bootstrapStderr)
    Stop-Process -Id $bootstrapProcess.Id -Force
    $bootstrapProcess.WaitForExit()
    $processIds.Remove($bootstrapProcess.Id) | Out-Null

    # The privileged account is created once; the validation process starts with bootstrap disabled.
    foreach ($name in @('PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED', 'PFX_BOOTSTRAP_OPERATOR_USERNAME', 'PFX_BOOTSTRAP_OPERATOR_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $campaignProcess = Start-ServiceProcess $campaignJarPath $campaignStdout $campaignStderr $campaignArgs
    $null = Wait-HttpHealth 'http://127.0.0.1:8081/api/health' $campaignProcess @($campaignStdout, $campaignStderr)

    # AI gets only the Campaign base URL; it does not receive the JWT signing key or DB credentials.
    foreach ($name in @(
        'PFX_JWT_SECRET', 'PFX_DB_URL', 'PFX_DB_USER', 'PFX_DB_PASSWORD',
        'MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD'
    )) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $env:PFX_CAMPAIGN_BASE_URL = 'http://127.0.0.1:8081'
    $aiProcess = Start-ServiceProcess $aiJarPath $aiStdout $aiStderr @()
    $null = Wait-HttpHealth 'http://127.0.0.1:8082/api/health' $aiProcess @($aiStdout, $aiStderr)

    $loginBody = @{ username = $operatorUsername; password = $operatorPassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/auth/login' `
        -ContentType 'application/json' -Body $loginBody
    $token = $login.data.token
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'Campaign login did not issue a token.' }
    $headers = @{ Authorization = "Bearer $token" }

    $campaignId = [long]20260924001
    $now = [DateTimeOffset]::UtcNow
    $createBody = @{
        id = $campaignId
        name = 'H5 local cross-process smoke'
        startsAt = $now.AddMinutes(-2).ToString('o')
        endsAt = $now.AddDays(1).ToString('o')
    } | ConvertTo-Json -Compress
    $created = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/campaigns' `
        -Headers $headers -ContentType 'application/json' -Body $createBody
    if ([long]$created.data.id -ne $campaignId) { throw 'Campaign did not persist the requested test record.' }

    $aiBody = @{ campaignId = $campaignId; question = 'Summarize this campaign.' } | ConvertTo-Json -Compress
    $summary = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/api/ai/campaign-summary' `
        -Headers $headers -ContentType 'application/json' -Body $aiBody
    if (-not $summary.data.simulated -or $summary.data.answer -notmatch 'H5 local cross-process smoke') {
        throw 'AI response did not include the persisted Campaign record.'
    }

    $session = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/api/ai/sessions' -Headers $headers
    $sessionId = $session.data.sessionId
    if ([string]::IsNullOrWhiteSpace($sessionId)) { throw 'AI did not create a conversation session.' }
    $firstMessage = @{
        campaignId = $campaignId
        question = 'Remember this campaign and summarize it.'
        expectedVersion = 0
    } | ConvertTo-Json -Compress
    $firstTurn = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/api/ai/sessions/$sessionId/messages" `
        -Headers $headers -ContentType 'application/json' -Body $firstMessage
    if ([long]$firstTurn.data.version -ne 1 -or -not $firstTurn.data.result.simulated) {
        throw 'The first session turn did not append a simulated result.'
    }
    $secondMessage = @{
        campaignId = $campaignId
        question = 'And what is its status?'
        expectedVersion = 1
    } | ConvertTo-Json -Compress
    $secondTurn = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/api/ai/sessions/$sessionId/messages" `
        -Headers $headers -ContentType 'application/json' -Body $secondMessage
    $sessionView = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:8082/api/ai/sessions/$sessionId" -Headers $headers
    if ([long]$secondTurn.data.version -ne 2 -or $sessionView.data.events.Count -ne 8 `
            -or $sessionView.data.events[0].content -ne 'Remember this campaign and summarize it.' `
            -or $sessionView.data.events[4].content -ne 'And what is its status?') {
        throw 'The second turn did not preserve the first turn in the same session.'
    }
    Invoke-RestMethod -Method Delete -Uri "http://127.0.0.1:8082/api/ai/sessions/$sessionId" -Headers $headers | Out-Null

    $anonymousStatus = 0
    try {
        Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/api/ai/campaign-summary' `
            -ContentType 'application/json' -Body $aiBody | Out-Null
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $anonymousStatus = [int]$_.Exception.Response.StatusCode
        }
    }
    if ($anonymousStatus -ne 401) { throw 'An anonymous AI request did not return HTTP 401.' }

    [pscustomobject]@{
        Result = 'PASS'
        Database = 'Disposable MySQL 8.4 container; Flyway migrations applied by Campaign'
        CampaignProcessId = $campaignProcess.Id
        AiProcessId = $aiProcess.Id
        Authentication = 'Campaign-issued JWT revalidated through the live /api/auth/me endpoint'
        CampaignRead = 'AI received the record created through the separate Campaign HTTP process'
        Conversation = 'Two authenticated turns, version CAS, and eight append-only events verified through AI HTTP'
        AiMode = 'Fake Model; simulated=true'
        AnonymousAiStatus = $anonymousStatus
        CredentialsOrTokenPrinted = $false
    } | Format-List
} finally {
    $token = $null
    foreach ($id in $processIds) {
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
    if ($containerStarted) { & docker stop --time 10 $containerName *> $null }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    foreach ($path in @($bootstrapStdout, $bootstrapStderr, $campaignStdout, $campaignStderr, $aiStdout, $aiStderr)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
}
