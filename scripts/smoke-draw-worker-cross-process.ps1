param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CampaignJar = 'backend/prizeflux-campaign/target/prizeflux-campaign-0.1.0-SNAPSHOT.jar',
    [string]$WorkerJar = 'backend/prizeflux-worker/target/prizeflux-worker-0.1.0-SNAPSHOT.jar',
    [ValidateRange(1, 500)][int]$RequestCount = 1,
    [ValidateRange(1, 100)][int]$Concurrency = 1,
    [ValidateRange(1, 500)][int]$PrizeStock = 1,
    [switch]$EnableRedisLimiter,
    [switch]$InjectTransientFulfillmentFailure,
    [switch]$InjectRedisOutage,
    [switch]$InjectKafkaOutage,
    [switch]$InjectCampaignMySqlOutage,
    [string]$EvidencePath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($PrizeStock -gt $RequestCount) { throw 'PrizeStock cannot exceed RequestCount for this benchmark scenario.' }
if ($Concurrency -gt $RequestCount) { throw 'Concurrency cannot exceed RequestCount.' }
$faultModes = @($InjectRedisOutage.IsPresent, $InjectKafkaOutage.IsPresent, $InjectCampaignMySqlOutage.IsPresent)
if (@($faultModes | Where-Object { $_ }).Count -gt 1) {
    throw 'Inject only one dependency outage per isolated run.'
}
if ($InjectTransientFulfillmentFailure -and @($faultModes | Where-Object { $_ }).Count -gt 0) {
    throw 'Run simulated fulfillment failure separately from dependency outage scenarios.'
}
if ($InjectRedisOutage -and -not $EnableRedisLimiter) { throw 'Redis outage injection requires -EnableRedisLimiter.' }
if (($InjectRedisOutage -or $InjectKafkaOutage -or $InjectCampaignMySqlOutage) `
        -and ($RequestCount -ne 1 -or $Concurrency -ne 1 -or $PrizeStock -ne 1)) {
    throw 'Fault-injection scenarios require exactly one request, one concurrent worker, and one prize.'
}
if ($InjectKafkaOutage -and $EnableRedisLimiter) { throw 'Kafka outage injection runs with the Redis limiter disabled to isolate the dependency.' }
if ($InjectCampaignMySqlOutage -and $EnableRedisLimiter) { throw 'Campaign MySQL outage injection runs with the Redis limiter disabled to isolate the dependency.' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Set JAVA_HOME to a JDK 17 installation first.' }
if ($EvidencePath) {
    if ([IO.Path]::IsPathRooted($EvidencePath)) { throw 'EvidencePath must be a repository-relative path.' }
    $evidenceFullPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $EvidencePath))
    $baselineRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'docs/baseline'))
    if (-not $evidenceFullPath.StartsWith($baselineRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'EvidencePath must stay inside docs/baseline.'
    }
    if ([IO.Path]::GetExtension($evidenceFullPath) -ne '.json') { throw 'EvidencePath must use a .json extension.' }
    if (Test-Path -LiteralPath $evidenceFullPath) { throw 'EvidencePath already exists; choose a new name to preserve prior evidence.' }
}
$java = Join-Path $JavaHome 'bin/java.exe'
$campaignJarPath = (Resolve-Path (Join-Path $repoRoot $CampaignJar)).Path
$workerJarPath = (Resolve-Path (Join-Path $repoRoot $WorkerJar)).Path
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JAVA_HOME does not contain bin/java.exe.' }

# Isolated ports only. Never attach to or stop the user's Compose services.
$requiredPorts = @(13306, 13307, 19092, 8081)
if ($EnableRedisLimiter) { $requiredPorts += 16379 }
$busyPorts = foreach ($port in $requiredPorts) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { $port }
}
if ($busyPorts) { throw "Required smoke-test port(s) already in use: $($busyPorts -join ', ')." }

$tag = [guid]::NewGuid().ToString('N').Substring(0, 10)
$campaignDb = "pfx-campaign-$tag"
$workerDb = "pfx-worker-$tag"
$kafka = "pfx-kafka-$tag"
$redis = "pfx-redis-$tag"
$logBase = Join-Path ([IO.Path]::GetTempPath()) "pfx-draw-worker-$tag"
$logs = @{
    bootstrapOut = "$logBase-bootstrap.out.log"; bootstrapErr = "$logBase-bootstrap.err.log"
    campaignOut = "$logBase-campaign.out.log"; campaignErr = "$logBase-campaign.err.log"
    workerOut = "$logBase-worker.out.log"; workerErr = "$logBase-worker.err.log"
}
$processIds = [System.Collections.Generic.List[int]]::new()
$startedContainers = [System.Collections.Generic.List[string]]::new()
$envNames = @(
    'MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_PWD',
    'PFX_DB_URL', 'PFX_DB_USER', 'PFX_DB_PASSWORD', 'PFX_WORKER_DB_URL', 'PFX_WORKER_DB_USER',
    'PFX_WORKER_DB_PASSWORD', 'PFX_JWT_SECRET', 'PFX_BOOTSTRAP_OPERATOR_USERNAME',
    'PFX_BOOTSTRAP_OPERATOR_PASSWORD', 'PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED',
    'KAFKA_BOOTSTRAP_SERVERS', 'PFX_DRAW_RATE_LIMIT_ENABLED', 'PFX_DRAW_RATE_LIMIT_MAX_REQUESTS',
    'PFX_DRAW_RATE_LIMIT_WINDOW_SECONDS', 'PFX_REDIS_HOST', 'PFX_REDIS_PORT',
    'PFX_DB_CONNECTION_TIMEOUT_MS'
)
$previousEnvironment = @{}
foreach ($name in $envNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$rootPassword = $null
$campaignPassword = $null
$workerPassword = $null
$jwtSecret = $null
$operatorPassword = $null

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

function Wait-HttpHealth([string]$uri, [System.Diagnostics.Process]$process, [string[]]$logPaths) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) { throw "A service exited before becoming healthy (exit $($process.ExitCode))." }
        try { return Invoke-RestMethod -Uri $uri -TimeoutSec 2 }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw 'Timed out waiting for a local service health endpoint.'
}

function Wait-MySql([string]$containerName) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        & docker exec $containerName mysqladmin ping -h 127.0.0.1 --silent *> $null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 2
    }
    throw 'A disposable MySQL container did not become ready.'
}

function Wait-TcpService([string]$hostName, [int]$port, [int]$timeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $tcp.BeginConnect($hostName, $port, $null, $null)
            if ($connect.AsyncWaitHandle.WaitOne(1000) -and $tcp.Connected) {
                $tcp.EndConnect($connect)
                return
            }
        } catch { }
        finally { $tcp.Dispose() }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for local service $hostName`:$port."
}

function Wait-Redis([string]$containerName) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        $redisReply = & docker exec $containerName redis-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $redisReply -eq 'PONG') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'The disposable Redis service did not become ready.'
}

function Invoke-DbScalar([string]$containerName, [string]$dbUser, [string]$dbPassword, [string]$database, [string]$sql) {
    $env:MYSQL_PWD = $dbPassword
    $result = & docker exec --env MYSQL_PWD $containerName mysql --batch --skip-column-names `
        --user=$dbUser --database=$database --execute=$sql
    if ($LASTEXITCODE -ne 0) { throw 'A local smoke-test database query failed.' }
    return (($result | Out-String).Trim())
}

function Invoke-ConcurrentDraws([string]$token, [long]$campaignId, [int]$count, [int]$concurrency) {
    $httpClient = [System.Net.Http.HttpClient]::new()
    $pool = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspacePool(1, $concurrency)
    $pool.Open()
    $jobs = [System.Collections.Generic.List[object]]::new()
    $results = [System.Collections.Generic.List[object]]::new()
    $requestScript = {
        param($client, $uri, $bearer, $campaign, $ordinal)
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $request = $null
        $response = $null
        try {
            $body = @{ campaignId = $campaign; requestKey = [guid]::NewGuid().ToString('N') } | ConvertTo-Json -Compress
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $bearer)
            $request.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $document = $responseBody | ConvertFrom-Json
            $timer.Stop()
            [pscustomobject]@{
                Index = $ordinal; HttpStatus = [int]$response.StatusCode
                Outcome = [string]$document.data.outcome; DrawId = [long]$document.data.requestId
                LatencyMs = $timer.Elapsed.TotalMilliseconds; ErrorType = ''
            }
        } catch {
            $timer.Stop()
            [pscustomobject]@{
                Index = $ordinal; HttpStatus = 0; Outcome = 'HTTP_ERROR'; DrawId = 0
                LatencyMs = $timer.Elapsed.TotalMilliseconds; ErrorType = $_.Exception.GetType().Name
            }
        } finally {
            if ($response) { $response.Dispose() }
            if ($request) { $request.Dispose() }
        }
    }

    try {
        $uri = 'http://127.0.0.1:8081/api/draws'
        for ($index = 1; $index -le $count; $index++) {
            $powerShell = [PowerShell]::Create()
            $powerShell.RunspacePool = $pool
            $null = $powerShell.AddScript($requestScript.ToString())
            $null = $powerShell.AddArgument($httpClient)
            $null = $powerShell.AddArgument($uri)
            $null = $powerShell.AddArgument($token)
            $null = $powerShell.AddArgument($campaignId)
            $null = $powerShell.AddArgument($index)
            $jobs.Add([pscustomobject]@{ PowerShell = $powerShell; Handle = $powerShell.BeginInvoke() })
        }
        foreach ($job in $jobs) {
            foreach ($item in $job.PowerShell.EndInvoke($job.Handle)) { $results.Add($item) }
        }
    } finally {
        foreach ($job in $jobs) { $job.PowerShell.Dispose() }
        $pool.Close()
        $pool.Dispose()
        $httpClient.Dispose()
    }
    return $results.ToArray()
}

function Invoke-DrawPipelineWarmup([string]$token, [long]$campaignId) {
    $client = [System.Net.Http.HttpClient]::new()
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, 'http://127.0.0.1:8081/api/draws')
    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
        $body = @{ campaignId = $campaignId } | ConvertTo-Json -Compress
        $request.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 400) {
            throw 'The out-of-band invalid draw warmup did not stop at request validation.'
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Write-BenchmarkEvidence([string]$path, [object]$report) {
    if (-not $path) { return }
    $directory = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $directory -Force
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
}

function Get-Percentile([double[]]$values, [double]$percentile) {
    $ordered = @($values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($percentile * $ordered.Count) - 1)
    return [Math]::Round($ordered[$index], 2)
}

try {
    $rootPassword = New-Secret
    $campaignPassword = New-Secret
    $workerPassword = New-Secret
    $jwtSecret = New-Secret
    $operatorPassword = New-Secret
    $operatorUsername = "op$tag"

    # Each service owns a separate disposable MySQL schema/container.
    $env:MYSQL_ROOT_PASSWORD = $rootPassword
    $env:MYSQL_DATABASE = 'prizeflux'
    $env:MYSQL_USER = 'prizeflux'
    $env:MYSQL_PASSWORD = $campaignPassword
    $campaignDbArgs = @('run')
    if (-not $InjectCampaignMySqlOutage) { $campaignDbArgs += '--rm' }
    $campaignDbArgs += @('--detach', '--name', $campaignDb, '--publish', '127.0.0.1:13306:3306',
        '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_DATABASE', '--env', 'MYSQL_USER', '--env', 'MYSQL_PASSWORD', 'mysql:8.4')
    & docker @campaignDbArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Campaign MySQL container.' }
    $startedContainers.Add($campaignDb)

    $env:MYSQL_ROOT_PASSWORD = $rootPassword
    $env:MYSQL_DATABASE = 'prizeflux_worker'
    $env:MYSQL_USER = 'prizeflux_worker'
    $env:MYSQL_PASSWORD = $workerPassword
    & docker run --rm --detach --name $workerDb --publish '127.0.0.1:13307:3306' `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD mysql:8.4 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Worker MySQL container.' }
    $startedContainers.Add($workerDb)
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Wait-MySql $campaignDb
    Wait-MySql $workerDb

    if ($EnableRedisLimiter) {
        $redisArgs = @('run', '--rm', '--detach', '--name', $redis, '--publish', '127.0.0.1:16379:6379', 'redis:7.4-alpine')
        & docker @redisArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Redis container.' }
        $startedContainers.Add($redis)
        Wait-Redis $redis
    }

    # Single-node KRaft broker, advertised separately for in-container and host clients.
    $kafkaArgs = @(
        'run', '--rm', '--detach', '--name', $kafka, '--publish', '127.0.0.1:19092:9094',
        '--env', 'CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk',
        '--env', 'KAFKA_NODE_ID=1',
        '--env', 'KAFKA_PROCESS_ROLES=broker,controller',
        '--env', 'KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:9094,CONTROLLER://:9093',
        '--env', 'KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:9092,EXTERNAL://localhost:19092',
        '--env', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
        '--env', 'KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL',
        '--env', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
        '--env', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
        '--env', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
        '--env', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
        '--env', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
        '--env', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0',
        'apache/kafka:3.9.1'
    )
    & docker @kafkaArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Kafka broker.' }
    $startedContainers.Add($kafka)
    Wait-TcpService '127.0.0.1' 19092 120

    $env:PFX_DB_URL = 'jdbc:mysql://127.0.0.1:13306/prizeflux'
    $env:PFX_DB_USER = 'prizeflux'
    $env:PFX_DB_PASSWORD = $campaignPassword
    $env:PFX_DB_CONNECTION_TIMEOUT_MS = '3000'
    $env:PFX_JWT_SECRET = $jwtSecret
    $env:PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED = 'true'
    $env:PFX_BOOTSTRAP_OPERATOR_USERNAME = $operatorUsername
    $env:PFX_BOOTSTRAP_OPERATOR_PASSWORD = $operatorPassword
    $env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092'
    $env:PFX_DRAW_RATE_LIMIT_ENABLED = [string]$EnableRedisLimiter.IsPresent.ToString().ToLowerInvariant()
    $env:PFX_DRAW_RATE_LIMIT_MAX_REQUESTS = [string]($RequestCount + 5)
    $env:PFX_DRAW_RATE_LIMIT_WINDOW_SECONDS = '3600'
    if ($EnableRedisLimiter) {
        $env:PFX_REDIS_HOST = '127.0.0.1'
        $env:PFX_REDIS_PORT = '16379'
    }
    $campaignArgs = @('--spring.profiles.active=local-full')
    $bootstrap = Start-ServiceProcess $campaignJarPath $logs.bootstrapOut $logs.bootstrapErr $campaignArgs
    $null = Wait-HttpHealth 'http://127.0.0.1:8081/api/health' $bootstrap @($logs.bootstrapOut, $logs.bootstrapErr)
    $operatorReady = $false
    $operatorDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $operatorDeadline) {
        $operatorCount = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM app_user WHERE username = '$operatorUsername' AND role = 'OPERATOR'"
        if ($operatorCount -eq '1') { $operatorReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $operatorReady) { throw 'The one-time bootstrap process became healthy without persisting its operator account.' }
    Stop-Process -Id $bootstrap.Id -Force
    $bootstrap.WaitForExit()
    $processIds.Remove($bootstrap.Id) | Out-Null

    foreach ($name in @('PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED', 'PFX_BOOTSTRAP_OPERATOR_USERNAME', 'PFX_BOOTSTRAP_OPERATOR_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $campaign = Start-ServiceProcess $campaignJarPath $logs.campaignOut $logs.campaignErr $campaignArgs
    $null = Wait-HttpHealth 'http://127.0.0.1:8081/api/health' $campaign @($logs.campaignOut, $logs.campaignErr)

    $env:PFX_WORKER_DB_URL = 'jdbc:mysql://127.0.0.1:13307/prizeflux_worker'
    $env:PFX_WORKER_DB_USER = 'prizeflux_worker'
    $env:PFX_WORKER_DB_PASSWORD = $workerPassword
    $workerArguments = @('--spring.profiles.active=local-full')
    if ($InjectTransientFulfillmentFailure) {
        $workerArguments += '--prizeflux.worker.fulfillment.simulated-fail-first-attempt=true'
    }
    $worker = Start-ServiceProcess $workerJarPath $logs.workerOut $logs.workerErr $workerArguments
    $workerSchemaDeadline = [DateTime]::UtcNow.AddSeconds(120)
    $workerSchemaReady = $false
    while ([DateTime]::UtcNow -lt $workerSchemaDeadline) {
        $worker.Refresh()
        if ($worker.HasExited) { throw "Worker exited before its Flyway schema became ready (exit $($worker.ExitCode))." }
        $tableCount = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'prizeflux_worker' AND table_name = 'fulfillment'"
        if ($tableCount -eq '1') { $workerSchemaReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $workerSchemaReady) { throw 'Timed out waiting for Worker Flyway to create the fulfillment ledger.' }

    $operatorLogin = @{ username = $operatorUsername; password = $operatorPassword } | ConvertTo-Json -Compress
    $operator = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/auth/login' `
        -ContentType 'application/json' -Body $operatorLogin
    $operatorHeaders = @{ Authorization = "Bearer $($operator.data.token)" }

    # A fixed test-only BCrypt hash authenticates this disposable USER; no account or password is reused.
    $userSql = 'INSERT INTO app_user (username, password_hash, role, state) VALUES (''smoke.user'', ''$2a$10$nZsGVyRD3UDUZatvdP.ZuexcAQ81RRw5KsSbvLkSEB47rl4Wb6ms.'', ''USER'', ''ACTIVE'')'
    $env:MYSQL_PWD = $campaignPassword
    & docker exec --env MYSQL_PWD $campaignDb mysql --user=prizeflux --database=prizeflux --execute=$userSql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the disposable USER account.' }
    $userLogin = @{ username = 'smoke.user'; password = 'smoke-user-password' } | ConvertTo-Json -Compress
    $user = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/auth/login' `
        -ContentType 'application/json' -Body $userLogin
    $userHeaders = @{ Authorization = "Bearer $($user.data.token)" }

    $randomId = [Convert]::ToInt64($tag.Substring(0, 8), 16) + 202609240000
    $campaignId = $randomId
    $prizeId = $randomId + 1
    $now = [DateTimeOffset]::UtcNow
    $campaignBody = @{
        id = $campaignId; name = 'Cross-process fulfillment smoke'
        startsAt = $now.AddMinutes(-2).ToString('o'); endsAt = $now.AddHours(1).ToString('o')
    } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/campaigns' `
        -Headers $operatorHeaders -ContentType 'application/json' -Body $campaignBody
    $configuration = @{
        dailyLimit = $RequestCount
        prizes = @(@{ id = $prizeId; name = 'Benchmark prize'; totalStock = $PrizeStock; probability = 1.0 })
    } | ConvertTo-Json -Depth 5 -Compress
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/campaigns/$campaignId/configuration" `
        -Headers $operatorHeaders -ContentType 'application/json' -Body $configuration
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/campaigns/$campaignId/publish" `
        -Headers $operatorHeaders

    # Apply identical health, login, and campaign-read warmup in limiter-on/off runs.
    $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/api/health'
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:8081/api/campaigns/$campaignId" -Headers $userHeaders
    Invoke-DrawPipelineWarmup $user.data.token $campaignId

    $faultInjection = $null
    if ($InjectRedisOutage) {
        $outageStartedAt = [DateTimeOffset]::UtcNow
        & docker stop --time 2 $redis | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the disposable Redis service for outage injection.' }
        $failedProbeTimer = [Diagnostics.Stopwatch]::StartNew()
        $failedProbe = @(Invoke-ConcurrentDraws $user.data.token $campaignId 1 1)
        $failedProbeTimer.Stop()
        if ($failedProbe.Count -ne 1 -or $failedProbe[0].HttpStatus -ne 503) {
            throw 'Redis outage did not produce the expected fail-closed HTTP 503.'
        }
        $faultDrawRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $campaignId"
        $faultOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (SELECT id FROM draw_result WHERE campaign_id = $campaignId)"
        $faultStock = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id = $campaignId"
        if ($faultDrawRows -ne '0' -or $faultOutboxRows -ne '0' -or $faultStock -ne [string]$PrizeStock) {
            throw 'A Redis-rejected request changed draw, inventory, or Outbox state.'
        }
        & docker @redisArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart the disposable Redis service after outage injection.' }
        Wait-Redis $redis
        $faultInjection = [pscustomobject]@{
            Dependency = 'Redis'; ObservedHttpStatus = [int]$failedProbe[0].HttpStatus
            FailedRequestLatencyMs = [Math]::Round($failedProbeTimer.Elapsed.TotalMilliseconds, 2)
            DrawRowsBeforeRecovery = [int]$faultDrawRows; OutboxRowsBeforeRecovery = [int]$faultOutboxRows
            InventoryRemainingBeforeRecovery = [int]$faultStock
            Recovery = 'Redis process restarted; subsequent draw is verified in the normal chain'
            OutageDurationMs = [Math]::Round(([DateTimeOffset]::UtcNow - $outageStartedAt).TotalMilliseconds, 2)
        }
    }
    if ($InjectCampaignMySqlOutage) {
        $outageStartedAt = [DateTimeOffset]::UtcNow
        & docker stop --time 2 $campaignDb | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the disposable Campaign MySQL service for outage injection.' }
        $failedProbeTimer = [Diagnostics.Stopwatch]::StartNew()
        $failedProbe = @(Invoke-ConcurrentDraws $user.data.token $campaignId 1 1)
        $failedProbeTimer.Stop()
        if ($failedProbe.Count -ne 1 -or $failedProbe[0].HttpStatus -lt 500 -or $failedProbe[0].HttpStatus -ge 600) {
            throw 'Campaign MySQL outage did not fail the draw request with an HTTP 5xx response.'
        }
        & docker start $campaignDb | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart the disposable Campaign MySQL service after outage injection.' }
        Wait-MySql $campaignDb
        $faultDrawRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $campaignId"
        $faultOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (SELECT id FROM draw_result WHERE campaign_id = $campaignId)"
        $faultStock = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id = $campaignId"
        if ($faultDrawRows -ne '0' -or $faultOutboxRows -ne '0' -or $faultStock -ne [string]$PrizeStock) {
            throw 'The failed MySQL-dependent draw left unexpected durable business state.'
        }
        $faultInjection = [pscustomobject]@{
            Dependency = 'Campaign MySQL'; ObservedHttpStatus = [int]$failedProbe[0].HttpStatus
            FailedRequestLatencyMs = [Math]::Round($failedProbeTimer.Elapsed.TotalMilliseconds, 2)
            DrawRowsBeforeRecovery = [int]$faultDrawRows; OutboxRowsBeforeRecovery = [int]$faultOutboxRows
            InventoryRemainingBeforeRecovery = [int]$faultStock
            Recovery = 'Campaign MySQL container restarted with its original disposable volume; subsequent draw is verified in the normal chain'
            OutageDurationMs = [Math]::Round(([DateTimeOffset]::UtcNow - $outageStartedAt).TotalMilliseconds, 2)
        }
    }
    if ($InjectKafkaOutage) {
        $kafkaOutageStartedAt = [DateTimeOffset]::UtcNow
        & docker stop --time 2 $kafka | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the disposable Kafka broker for outage injection.' }
    }

    $batchTimer = [Diagnostics.Stopwatch]::StartNew()
    $samples = @(Invoke-ConcurrentDraws $user.data.token $campaignId $RequestCount $Concurrency)
    $batchTimer.Stop()
    $latencies = [double[]]@($samples | ForEach-Object { [double]$_.LatencyMs })
    $outcomeCounts = @{}
    foreach ($sample in $samples) {
        if (-not $outcomeCounts.ContainsKey($sample.Outcome)) { $outcomeCounts[$sample.Outcome] = 0 }
        $outcomeCounts[$sample.Outcome]++
    }
    $expectedSoldOut = $RequestCount - $PrizeStock
    $httpFailures = @($samples | Where-Object { $_.HttpStatus -ne 200 }).Count
    $actualWon = if ($outcomeCounts.ContainsKey('WON')) { [int]$outcomeCounts.WON } else { 0 }
    $actualSoldOut = if ($outcomeCounts.ContainsKey('SOLD_OUT')) { [int]$outcomeCounts.SOLD_OUT } else { 0 }
    $actualNoPrize = if ($outcomeCounts.ContainsKey('NO_PRIZE')) { [int]$outcomeCounts.NO_PRIZE } else { 0 }
    if ($httpFailures -ne 0 -or $actualWon -ne $PrizeStock -or $actualSoldOut -ne $expectedSoldOut -or $actualNoPrize -ne 0) {
        throw 'Concurrent draw HTTP/outcome counts did not match the deterministic stock-exhaustion scenario.'
    }

    if ($InjectKafkaOutage) {
        $outboxRetryDeadline = [DateTime]::UtcNow.AddSeconds(35)
        $outboxRetryState = ''
        while ([DateTime]::UtcNow -lt $outboxRetryDeadline) {
            $outboxRetryState = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_type = 'PrizeFulfillmentRequested' AND aggregate_id IN (SELECT id FROM draw_result WHERE campaign_id = $campaignId)"
            if ($outboxRetryState -match '^PENDING:([1-9][0-9]*)$') { break }
            if ($outboxRetryState.StartsWith('DEAD:')) { throw 'Outbox exhausted retries while Kafka was intentionally unavailable.' }
            Start-Sleep -Milliseconds 300
        }
        $attemptMatch = [regex]::Match($outboxRetryState, '^PENDING:([1-9][0-9]*)$')
        if (-not $attemptMatch.Success) {
            throw 'Kafka outage did not leave the Outbox event in a retryable PENDING state.'
        }
        $attemptsBeforeRecovery = [int]$attemptMatch.Groups[1].Value
        & docker @kafkaArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart the disposable Kafka broker after outage injection.' }
        Wait-TcpService '127.0.0.1' 19092 120
        $faultInjection = [pscustomobject]@{
            Dependency = 'Kafka'; OutboxStateBeforeRecovery = 'PENDING'
            PublishAttemptsBeforeRecovery = $attemptsBeforeRecovery
            Recovery = 'Kafka broker restarted; Outbox and Worker completion are verified in the normal chain'
            TimeToBrokerPortMs = [Math]::Round(([DateTimeOffset]::UtcNow - $kafkaOutageStartedAt).TotalMilliseconds, 2)
        }
    }

    $fulfillmentTimeoutSeconds = if ($InjectKafkaOutage) { 90 } else { 45 }
    $deadline = [DateTime]::UtcNow.AddSeconds($fulfillmentTimeoutSeconds)
    $campaignDrawRows = '0'
    $remainingStock = '-1'
    $sentOutboxRows = '0'
    $workerRows = '0'
    $workerSucceededRows = '0'
    $workerAttemptCount = '0'
    $manualReviewTasks = '0'
    while ([DateTime]::UtcNow -lt $deadline) {
        $campaignDrawRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $campaignId"
        $remainingStock = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id = $campaignId"
        $sentOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'PrizeFulfillmentRequested' AND aggregate_id IN (SELECT id FROM draw_result WHERE campaign_id = $campaignId)"
        $workerRows = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
            "SELECT COUNT(*) FROM fulfillment WHERE campaign_id = $campaignId"
        $workerSucceededRows = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
            "SELECT COUNT(*) FROM fulfillment WHERE campaign_id = $campaignId AND status = 'SUCCEEDED'"
        $workerAttemptCount = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
            "SELECT COALESCE(SUM(attempt_count), 0) FROM fulfillment WHERE campaign_id = $campaignId"
        $manualReviewTasks = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
            "SELECT COUNT(*) FROM fulfillment_compensation_task task JOIN fulfillment f ON f.id = task.fulfillment_id WHERE f.campaign_id = $campaignId"
        if ($campaignDrawRows -eq [string]$RequestCount -and $sentOutboxRows -eq [string]$PrizeStock `
                -and $workerRows -eq [string]$PrizeStock -and $workerSucceededRows -eq [string]$PrizeStock) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($campaignDrawRows -ne [string]$RequestCount -or $remainingStock -ne '0' `
            -or $sentOutboxRows -ne [string]$PrizeStock -or $workerRows -ne [string]$PrizeStock `
            -or $workerSucceededRows -ne [string]$PrizeStock) {
        throw 'Timed out or failed correctness checks for draw rows, inventory, Outbox, and Worker ledger.'
    }
    $attemptsPerPrize = if ($InjectTransientFulfillmentFailure) { 2 } else { 1 }
    $expectedAttempts = $PrizeStock * $attemptsPerPrize
    if ($workerAttemptCount -ne [string]$expectedAttempts -or $manualReviewTasks -ne '0') {
        throw 'Worker attempt/review state did not match the selected simulated fulfillment scenario.'
    }
    $duplicateCount = Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' `
        "SELECT COUNT(*) FROM (SELECT draw_id FROM fulfillment WHERE campaign_id = $campaignId GROUP BY draw_id HAVING COUNT(*) > 1) AS duplicates"
    if ($duplicateCount -ne '0') { throw 'The Worker ledger contains duplicate fulfillment rows for a draw.' }

    $elapsedMs = [Math]::Round($batchTimer.Elapsed.TotalMilliseconds, 2)
    $requestRate = if ($elapsedMs -gt 0) { [Math]::Round(1000 * $RequestCount / $elapsedMs, 2) } else { 0 }
    $javaVersion = (& $java -version 2>&1 | Out-String).Trim()
    $dockerVersion = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
    $report = [pscustomobject]@{
        Result = 'PASS'
        Scenario = if ($InjectTransientFulfillmentFailure) {
            'Authenticated draw plus one injected transient simulated fulfillment failure and durable retry recovery'
        } else { 'Authenticated concurrent draw HTTP benchmark with isolated Campaign/Worker MySQL and Kafka' }
        Environment = [pscustomobject]@{
            OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
            JavaHome = $JavaHome; JavaVersion = $javaVersion; ProcessorCount = [Environment]::ProcessorCount
            DockerEngine = $dockerVersion; MySqlImage = 'mysql:8.4'
            RedisImage = if ($EnableRedisLimiter) { 'redis:7.4-alpine' } else { 'disabled' }
            Broker = 'Disposable single-node Kafka 3.9.1'; FulfillmentMode = 'SIMULATED'
        }
        Configuration = [pscustomobject]@{
            RedisLimiterEnabled = [bool]$EnableRedisLimiter; RequestCount = $RequestCount
            Concurrency = $Concurrency; PrizeStock = $PrizeStock; UserDailyLimit = $RequestCount
            SimulatedFailFirstFulfillmentAttempt = [bool]$InjectTransientFulfillmentFailure
            RateLimitMaxRequests = $RequestCount + 5; RateLimitWindowSeconds = 3600
            RedisConnectTimeout = '1s'; RedisCommandTimeout = '1s'; CampaignDbConnectionTimeoutMs = 3000
            Warmup = 'Health GET, authenticated Campaign GET, and one authenticated invalid draw stopped by validation before timed batch (warms Redis/Lettuce when enabled)'
        }
        Metrics = [pscustomobject]@{
            TotalElapsedMs = $elapsedMs; RequestsPerSecond = $requestRate
            P50Ms = Get-Percentile $latencies 0.50; P95Ms = Get-Percentile $latencies 0.95
            P99Ms = Get-Percentile $latencies 0.99; HttpFailures = $httpFailures
            Won = $actualWon; SoldOut = $actualSoldOut; NoPrize = $actualNoPrize
        }
        Correctness = [pscustomobject]@{
            DrawRows = [int]$campaignDrawRows; InventoryRemaining = [int]$remainingStock
            SentOutboxRows = [int]$sentOutboxRows; WorkerLedgerRows = [int]$workerRows
            WorkerSucceededRows = [int]$workerSucceededRows; WorkerAttemptCountTotal = [int]$workerAttemptCount
            ManualReviewTasks = [int]$manualReviewTasks; DuplicateDrawFulfillments = [int]$duplicateCount
        }
        FaultInjection = $faultInjection
        Samples = @($samples | Select-Object Index, HttpStatus, Outcome, DrawId, LatencyMs, ErrorType)
        CredentialsOrTokensPrinted = $false
        Note = 'Local benchmark only; not production capacity evidence and not evidence that Redis makes MySQL faster.'
    }
    Write-BenchmarkEvidence $evidenceFullPath $report

    [pscustomobject]@{
        Result = 'PASS'
        RequestCount = $RequestCount
        Concurrency = $Concurrency
        PrizeStock = $PrizeStock
        RedisLimiterEnabled = [bool]$EnableRedisLimiter
        TotalElapsedMs = $elapsedMs
        RequestsPerSecond = $requestRate
        P50Ms = Get-Percentile $latencies 0.50
        P95Ms = Get-Percentile $latencies 0.95
        P99Ms = Get-Percentile $latencies 0.99
        DrawRows = $campaignDrawRows
        InventoryRemaining = $remainingStock
        SentOutboxRows = $sentOutboxRows
        WorkerLedgerRows = $workerRows
        WorkerSucceededRows = $workerSucceededRows
        WorkerAttemptCountTotal = $workerAttemptCount
        ManualReviewTasks = $manualReviewTasks
        DuplicateDrawFulfillments = $duplicateCount
        InjectTransientFulfillmentFailure = [bool]$InjectTransientFulfillmentFailure
        FaultInjection = $faultInjection
        FulfillmentMode = 'SIMULATED (no external supplier delivery)'
        Broker = 'Disposable single-node Kafka 3.9.1'
        EvidencePath = if ($evidenceFullPath) { [IO.Path]::GetRelativePath($repoRoot, $evidenceFullPath) } else { '' }
        CredentialsOrTokensPrinted = $false
    } | Format-List
} finally {
    foreach ($id in $processIds) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    foreach ($containerName in $startedContainers) { & docker stop --time 10 $containerName *> $null }
    if ($InjectCampaignMySqlOutage) { & docker rm --force --volumes $campaignDb *> $null }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    foreach ($path in $logs.Values) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
}
