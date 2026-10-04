param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CampaignJar = 'backend/prizeflux-campaign/target/prizeflux-campaign-0.1.0-SNAPSHOT.jar',
    [string]$WorkerJar = 'backend/prizeflux-worker/target/prizeflux-worker-0.1.0-SNAPSHOT.jar',
    [ValidateRange(1, 500)][int]$RequestCount = 1,
    [ValidateRange(1, 100)][int]$Concurrency = 1,
    [ValidateRange(1, 100)][int]$QueueBenchmarkUserCount = 1,
    [ValidateRange(1, 100)][int]$QueueBenchmarkCampaignCount = 1,
    [ValidateRange(1, 500)][int]$PrizeStock = 1,
    [ValidateRange(0, 200)][int]$QueueArrivalRate = 0,
    [ValidateRange(10, 300)][int]$QueueArrivalDurationSeconds = 60,
    [ValidateRange(1, 60)][int]$OutboxRelayDelaySeconds = 5,
    [ValidateRange(0, 60000)][int]$OutboxRelayDelayMilliseconds = 0,
    [ValidateRange(1, 8)][int]$OutboxRelayParallelism = 1,
    [ValidateRange(1, 12)][int]$QueueConsumerConcurrency = 1,
    [ValidateRange(1, 100)][int]$QueueBenchmarkHikariMaximumPoolSize = 10,
    [string]$K6Path,
    [ValidateRange(0, 10000)][int]$QueueBacklogLimit = 0,
    [switch]$EnableRedisLimiter,
    [switch]$BenchmarkDrawQueue,
    [switch]$RequireQueueCapacityRejections,
    [switch]$InjectTransientFulfillmentFailure,
    [switch]$InjectRedisOutage,
    [switch]$InjectKafkaOutage,
    [switch]$InjectDrawQueueKafkaOutage,
    [switch]$InjectDrawQueueKafkaRebalance,
    [switch]$InjectCampaignMySqlOutage,
    [string]$EvidencePath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Import-Module (Join-Path $PSScriptRoot 'perf/CampaignRuntimeMetrics.psm1') -Force
$queueArrivalBenchmark = $QueueArrivalRate -gt 0
$relayCandidateFault = $InjectDrawQueueKafkaOutage.IsPresent
$relayConfigurationOverrideAllowed = $BenchmarkDrawQueue -or $relayCandidateFault
$queueConsumerConfigurationOverrideAllowed = $BenchmarkDrawQueue -or $relayCandidateFault
$hikariConfigurationOverrideAllowed = $queueArrivalBenchmark -or $relayCandidateFault
if ($OutboxRelayParallelism -ne 1 -and -not $relayConfigurationOverrideAllowed) {
    throw 'OutboxRelayParallelism requires -BenchmarkDrawQueue or -InjectDrawQueueKafkaOutage.'
}
$effectiveOutboxRelayDelayMilliseconds = $OutboxRelayDelaySeconds * 1000
if ($OutboxRelayDelayMilliseconds -gt 0) {
    if (-not $relayConfigurationOverrideAllowed) {
        throw 'OutboxRelayDelayMilliseconds requires -BenchmarkDrawQueue or -InjectDrawQueueKafkaOutage.'
    }
    if ($PSBoundParameters.ContainsKey('OutboxRelayDelaySeconds')) {
        throw 'Specify only one Outbox relay delay unit.'
    }
    $effectiveOutboxRelayDelayMilliseconds = $OutboxRelayDelayMilliseconds
}
$measuredRequestCount = if ($queueArrivalBenchmark) { $QueueArrivalRate * $QueueArrivalDurationSeconds } else { $RequestCount }
$warmupRequestCount = if ($queueArrivalBenchmark) { [Math]::Min(5, $QueueArrivalRate) * 30 } else { 0 }
$arrivalBoundaryAllowance = if ($queueArrivalBenchmark) { 2 } else { 0 }
$scenarioRequestCount = if ($queueArrivalBenchmark) {
    $measuredRequestCount + $warmupRequestCount + $arrivalBoundaryAllowance
} else {
    $RequestCount
}
if ($PrizeStock -gt $measuredRequestCount) { throw 'PrizeStock cannot exceed the measured request count.' }
if ($Concurrency -gt $scenarioRequestCount) { throw 'Concurrency cannot exceed the scenario request count.' }
if ($K6Path -and -not $queueArrivalBenchmark) { throw 'K6Path is available only with QueueArrivalRate.' }
if ($OutboxRelayDelaySeconds -ne 5 -and -not $relayConfigurationOverrideAllowed) {
    throw 'OutboxRelayDelaySeconds requires -BenchmarkDrawQueue or -InjectDrawQueueKafkaOutage.'
}
if ($QueueConsumerConcurrency -ne 1 -and -not $queueConsumerConfigurationOverrideAllowed) {
    throw 'QueueConsumerConcurrency requires -BenchmarkDrawQueue or -InjectDrawQueueKafkaOutage.'
}
if ($QueueBenchmarkHikariMaximumPoolSize -ne 10 -and -not $hikariConfigurationOverrideAllowed) {
    throw 'QueueBenchmarkHikariMaximumPoolSize requires QueueArrivalRate or -InjectDrawQueueKafkaOutage.'
}
if ($queueArrivalBenchmark) {
    if (-not $BenchmarkDrawQueue -or -not $EnableRedisLimiter) {
        throw 'QueueArrivalRate requires -BenchmarkDrawQueue and -EnableRedisLimiter.'
    }
    if ($QueueBacklogLimit -gt 0 -or $RequireQueueCapacityRejections) {
        throw 'The constant-arrival scenario is a normal-load measurement, not an overload-guard run.'
    }
    if ($InjectRedisOutage -or $InjectKafkaOutage -or $InjectDrawQueueKafkaOutage `
            -or $InjectDrawQueueKafkaRebalance -or $InjectCampaignMySqlOutage -or $InjectTransientFulfillmentFailure) {
        throw 'The constant-arrival scenario cannot be combined with fault injection.'
    }
    if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
        throw 'QueueArrivalRate requires a new EvidencePath under docs/baseline.'
    }
    if ($K6Path) {
        $k6ExecutablePath = (Resolve-Path -LiteralPath $K6Path).Path
    } else {
        $k6Command = Get-Command 'k6' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $k6Command) { throw 'Grafana k6 is required for QueueArrivalRate; install it or pass -K6Path.' }
        $k6ExecutablePath = $k6Command.Source
    }
    if (-not (Test-Path -LiteralPath $k6ExecutablePath -PathType Leaf)) { throw 'K6Path does not point to an executable file.' }
} elseif ($QueueArrivalDurationSeconds -ne 60) {
    throw 'QueueArrivalDurationSeconds is available only with QueueArrivalRate.'
}
if ($QueueBenchmarkUserCount -gt 1 -and -not $BenchmarkDrawQueue) {
    throw 'QueueBenchmarkUserCount is available only with -BenchmarkDrawQueue.'
}
if ($QueueBenchmarkCampaignCount -gt 1 -and -not $BenchmarkDrawQueue) {
    throw 'QueueBenchmarkCampaignCount is available only with -BenchmarkDrawQueue.'
}
if ($BenchmarkDrawQueue -and ($QueueBenchmarkUserCount -gt $scenarioRequestCount -or $QueueBenchmarkCampaignCount -gt $scenarioRequestCount)) {
    throw 'QueueBenchmarkUserCount and QueueBenchmarkCampaignCount cannot exceed the scenario request count.'
}
if ($BenchmarkDrawQueue -and $QueueBenchmarkCampaignCount -gt $PrizeStock) {
    throw 'Each benchmark campaign must have at least one prize in PrizeStock.'
}
$faultModes = @($InjectRedisOutage.IsPresent, $InjectKafkaOutage.IsPresent,
    $InjectDrawQueueKafkaOutage.IsPresent, $InjectDrawQueueKafkaRebalance.IsPresent,
    $InjectCampaignMySqlOutage.IsPresent)
if (@($faultModes | Where-Object { $_ }).Count -gt 1) {
    throw 'Inject only one dependency outage per isolated run.'
}
if ($InjectTransientFulfillmentFailure -and @($faultModes | Where-Object { $_ }).Count -gt 0) {
    throw 'Run simulated fulfillment failure separately from dependency outage scenarios.'
}
if ($InjectRedisOutage -and -not $EnableRedisLimiter) { throw 'Redis outage injection requires -EnableRedisLimiter.' }
if (($InjectRedisOutage -or $InjectKafkaOutage -or $InjectDrawQueueKafkaOutage `
        -or $InjectDrawQueueKafkaRebalance -or $InjectCampaignMySqlOutage) `
        -and ($RequestCount -ne 1 -or $Concurrency -ne 1 -or $PrizeStock -ne 1)) {
    throw 'Fault-injection scenarios require exactly one request, one concurrent worker, and one prize.'
}
if ($InjectKafkaOutage -and $EnableRedisLimiter) { throw 'Kafka outage injection runs with the Redis limiter disabled to isolate the dependency.' }
if ($InjectDrawQueueKafkaOutage -and $EnableRedisLimiter) { throw 'Draw-queue Kafka outage injection runs with the Redis limiter disabled to isolate the dependency.' }
if ($InjectDrawQueueKafkaRebalance -and $EnableRedisLimiter) { throw 'Draw-queue rebalance injection disables Redis admission to isolate Kafka consumer behavior.' }
if ($InjectCampaignMySqlOutage -and $EnableRedisLimiter) { throw 'Campaign MySQL outage injection runs with the Redis limiter disabled to isolate the dependency.' }
if ($BenchmarkDrawQueue -and -not $EnableRedisLimiter) {
    throw 'Draw-queue load benchmarking requires -EnableRedisLimiter so its real Redis admission guard is exercised.'
}
if (($QueueBacklogLimit -gt 0 -or $RequireQueueCapacityRejections) -and -not $BenchmarkDrawQueue) {
    throw 'QueueBacklogLimit and RequireQueueCapacityRejections are available only with -BenchmarkDrawQueue.'
}
if ($RequireQueueCapacityRejections -and $QueueBacklogLimit -eq 0) {
    throw 'RequireQueueCapacityRejections requires an explicit QueueBacklogLimit.'
}
if ($BenchmarkDrawQueue -and (@($faultModes | Where-Object { $_ }).Count -gt 0 -or $InjectTransientFulfillmentFailure)) {
    throw 'Run draw-queue load benchmarking separately from outage and transient-fulfillment scenarios.'
}
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
    if ($queueArrivalBenchmark) {
        $k6SummaryFullPath = [IO.Path]::ChangeExtension($evidenceFullPath, '.k6-summary.json')
        if (Test-Path -LiteralPath $k6SummaryFullPath) {
            throw 'The derived k6 summary file already exists; choose a different EvidencePath.'
        }
    }
}
$java = Join-Path $JavaHome 'bin/java.exe'
$campaignJarPath = (Resolve-Path (Join-Path $repoRoot $CampaignJar)).Path
$workerJarPath = (Resolve-Path (Join-Path $repoRoot $WorkerJar)).Path
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
$benchmarkArtifacts = if ($queueArrivalBenchmark) {
    [pscustomobject]@{
        SourceCommit = (& git -C $repoRoot rev-parse HEAD | Out-String).Trim()
        DirtyFileCount = @(& git -C $repoRoot status --porcelain).Count
        CampaignJarSha256 = (Get-FileHash -LiteralPath $campaignJarPath -Algorithm SHA256).Hash
        WorkerJarSha256 = (Get-FileHash -LiteralPath $workerJarPath -Algorithm SHA256).Hash
        OrchestratorSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        RuntimeMetricsModuleSha256 = (Get-FileHash -LiteralPath `
            (Join-Path $PSScriptRoot 'perf/CampaignRuntimeMetrics.psm1') -Algorithm SHA256).Hash
    }
} else { $null }

# Isolated loopback ports only. Never attach to or stop the user's services.
function Reserve-FreeLoopbackPort([System.Collections.Generic.List[int]] $ReservedPorts) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        # Port zero selects from Windows' default outbound ephemeral range (49152+).
        # A Java/Kafka/MySQL connection can take that port before the service binds.
        # Probe random lower ports without changing the host's TCP configuration.
        $candidate = Get-Random -Minimum 20000 -Maximum 45000
        if ($ReservedPorts.Contains($candidate)) { continue }
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
        try {
            $listener.Start()
            $port = [int]$listener.LocalEndpoint.Port
            if (-not $ReservedPorts.Contains($port)) {
                $ReservedPorts.Add($port)
                return $port
            }
        } catch [System.Net.Sockets.SocketException] {
            # Already bound or excluded by Windows; choose another candidate.
            continue
        } finally {
            $listener.Stop()
        }
    }
    throw 'Could not reserve a distinct loopback port for the disposable smoke test.'
}

$reservedPorts = [System.Collections.Generic.List[int]]::new()
$campaignDbPort = Reserve-FreeLoopbackPort $reservedPorts
$workerDbPort = Reserve-FreeLoopbackPort $reservedPorts
$kafkaPort = Reserve-FreeLoopbackPort $reservedPorts
$campaignPort = Reserve-FreeLoopbackPort $reservedPorts
$rebalanceCampaignPort = if ($InjectDrawQueueKafkaRebalance) { Reserve-FreeLoopbackPort $reservedPorts } else { $null }
$redisPort = if ($EnableRedisLimiter) { Reserve-FreeLoopbackPort $reservedPorts } else { $null }
$campaignBaseUrl = "http://127.0.0.1:$campaignPort"
$requiredPorts = @($campaignDbPort, $workerDbPort, $kafkaPort, $campaignPort)
if ($EnableRedisLimiter) { $requiredPorts += $redisPort }
if ($InjectDrawQueueKafkaRebalance) { $requiredPorts += $rebalanceCampaignPort }
$busyPorts = foreach ($port in $requiredPorts) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { $port }
}
if ($busyPorts) { throw "A dynamically selected smoke-test port became occupied: $($busyPorts -join ', ')." }

$tag = [guid]::NewGuid().ToString('N').Substring(0, 10)
$campaignDb = "pfx-campaign-$tag"
$workerDb = "pfx-worker-$tag"
$kafka = "pfx-kafka-$tag"
$redis = "pfx-redis-$tag"
$logBase = Join-Path ([IO.Path]::GetTempPath()) "pfx-draw-worker-$tag"
$logs = @{
    bootstrapOut = "$logBase-bootstrap.out.log"; bootstrapErr = "$logBase-bootstrap.err.log"
    campaignOut = "$logBase-campaign.out.log"; campaignErr = "$logBase-campaign.err.log"
    campaignConsumerOut = "$logBase-campaign-consumer.out.log"; campaignConsumerErr = "$logBase-campaign-consumer.err.log"
    rebalanceCampaignOut = "$logBase-campaign-rebalance.out.log"; rebalanceCampaignErr = "$logBase-campaign-rebalance.err.log"
    queueConsumerOut = "$logBase-queue-consumer.out.log"; queueConsumerErr = "$logBase-queue-consumer.err.log"
    workerOut = "$logBase-worker.out.log"; workerErr = "$logBase-worker.err.log"
    k6Run = "$logBase-k6-run.log"; k6Err = "$logBase-k6-run.err.log"; k6Users = "$logBase-k6-users.json"
}
$processIds = [System.Collections.Generic.List[int]]::new()
$startedContainers = [System.Collections.Generic.List[string]]::new()
$envNames = @(
    'MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_PWD',
    'PFX_DB_URL', 'PFX_DB_USER', 'PFX_DB_PASSWORD', 'PFX_WORKER_DB_URL', 'PFX_WORKER_DB_USER',
    'PFX_WORKER_DB_PASSWORD', 'PFX_JWT_SECRET', 'PFX_BOOTSTRAP_OPERATOR_USERNAME',
    'PFX_BOOTSTRAP_OPERATOR_PASSWORD', 'PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED',
    'PFX_DRAW_QUEUE_CONSUMER_ENABLED', 'PFX_DRAW_QUEUE_SCAN_INITIAL_DELAY_MS',
    'PFX_DRAW_QUEUE_SCAN_DELAY_MS', 'PFX_DRAW_QUEUE_PROTECTION_ENABLED', 'PFX_DRAW_QUEUE_CONCURRENCY',
    'PFX_DRAW_QUEUE_RATE_WINDOW_SECONDS', 'PFX_DRAW_QUEUE_USER_MAX_REQUESTS',
    'PFX_DRAW_QUEUE_CAMPAIGN_MAX_REQUESTS', 'PFX_DRAW_QUEUE_GLOBAL_MAX_REQUESTS',
    'PFX_DRAW_QUEUE_MAX_BACKLOG', 'PFX_DRAW_QUEUE_MAX_OLDEST_AGE_SECONDS',
    'PFX_DRAW_QUEUE_DB_POOL_HIGH_WATERMARK',
    'PFX_MYSQL_LOCK_METRICS_ENABLED', 'PFX_MYSQL_LOCK_METRICS_JDBC_URL',
    'PFX_MYSQL_LOCK_METRICS_USERNAME', 'PFX_MYSQL_LOCK_METRICS_PASSWORD',
    'PFX_MYSQL_LOCK_METRICS_SAMPLE_DELAY_MS',
    'PFX_K6_BASE_URL', 'PFX_K6_USERS_FILE', 'PFX_K6_CAMPAIGN_IDS', 'PFX_K6_RUN_PREFIX',
    'PFX_K6_RATE', 'PFX_K6_DURATION_SECONDS', 'PFX_K6_SUMMARY_PATH',
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
$mysqlLockObserverPassword = $null

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

function Get-QueueAssignedPartitions([string]$LogPath) {
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { return @() }
    $assignmentLines = @(Select-String -LiteralPath $LogPath -Pattern 'partitions assigned: \[([^\]]*)\]')
    if ($assignmentLines.Count -eq 0) { return @() }
    $latest = $assignmentLines[-1].Matches[0].Groups[1].Value
    return @([regex]::Matches($latest, 'prizeflux\.draw\.queued-\d+') | ForEach-Object Value)
}

function Get-QueueAssignedPartitionUnion([string]$LogPath, [int]$ExpectedConsumers = 1) {
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { return @() }
    $assignmentLines = @(Select-String -LiteralPath $LogPath -Pattern 'partitions assigned: \[([^\]]*)\]')
    if ($assignmentLines.Count -lt $ExpectedConsumers) { return @() }
    $latestAssignments = @($assignmentLines | Select-Object -Last $ExpectedConsumers)
    $partitionIds = @($latestAssignments |
        ForEach-Object { [regex]::Matches($_.Matches[0].Groups[1].Value, 'prizeflux\.draw\.queued-\d+') } |
        ForEach-Object Value | Sort-Object -Unique)
    return $partitionIds
}

function Get-QueueConsumerGeneration([string]$LogPath) {
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { return 0 }
    $joinedLines = @(Select-String -LiteralPath $LogPath `
        -Pattern 'Successfully joined group with generation Generation\{generationId=(\d+)')
    if ($joinedLines.Count -eq 0) { return 0 }
    $generationIds = @($joinedLines | ForEach-Object { [int]$_.Matches[0].Groups[1].Value })
    return [int](($generationIds | Measure-Object -Maximum).Maximum)
}

function Get-DrawQueueProtectionReasonCounts([string[]]$LogPaths) {
    $counts = [ordered]@{}
    foreach ($path in $LogPaths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        foreach ($line in Get-Content -LiteralPath $path) {
            if ($line -match 'draw_queue_protection_rejected code=PFX-9001 reason=([A-Z_]+)') {
                $reason = $Matches[1]
                if (-not $counts.Contains($reason)) { $counts[$reason] = 0 }
                $counts[$reason]++
            }
        }
    }
    return $counts
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
        $uri = "$campaignBaseUrl/api/draws"
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

function Invoke-ConcurrentDrawQueueRequests([string[]]$tokens, [long[]]$campaignIds, [int]$count, [int]$concurrency) {
    $httpClient = [System.Net.Http.HttpClient]::new()
    $httpClient.Timeout = [TimeSpan]::FromSeconds(30)
    $pool = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspacePool(1, $concurrency)
    $pool.Open()
    $jobs = [System.Collections.Generic.List[object]]::new()
    $results = [System.Collections.Generic.List[object]]::new()
    $requestScript = {
        param($client, $uri, $bearer, $campaign, $ordinal, $userSlot, $campaignSlot)
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $request = $null
        $response = $null
        try {
            $requestKey = [guid]::NewGuid().ToString('N')
            $body = @{ campaignId = $campaign; requestKey = $requestKey } | ConvertTo-Json -Compress
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $bearer)
            $request.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $document = $responseBody | ConvertFrom-Json
            $timer.Stop()
            [pscustomobject]@{
                Index = $ordinal; UserSlot = $userSlot; CampaignSlot = $campaignSlot; HttpStatus = [int]$response.StatusCode
                QueueStatus = if ($document.data) { [string]$document.data.status } else { 'REJECTED' }
                QueueRequestId = if ($document.data) { [string]$document.data.queueRequestId } else { '' }
                ErrorCode = if ($document.code) { [string]$document.code } else { '' }
                LatencyMs = $timer.Elapsed.TotalMilliseconds; ErrorType = ''
            }
        } catch {
            $timer.Stop()
            [pscustomobject]@{
                Index = $ordinal; UserSlot = $userSlot; CampaignSlot = $campaignSlot; HttpStatus = 0; QueueStatus = 'HTTP_ERROR'
                QueueRequestId = ''; ErrorCode = ''; LatencyMs = $timer.Elapsed.TotalMilliseconds
                ErrorType = $_.Exception.GetType().Name
            }
        } finally {
            if ($response) { $response.Dispose() }
            if ($request) { $request.Dispose() }
        }
    }

    try {
        $uri = "$campaignBaseUrl/api/draw-queue"
        for ($index = 1; $index -le $count; $index++) {
            $userIndex = ($index - 1) % $tokens.Count
            $userToken = $tokens[$userIndex]
            $userSlot = $userIndex + 1
            $campaignIndex = ($index - 1) % $campaignIds.Count
            $requestCampaignId = $campaignIds[$campaignIndex]
            $campaignSlot = $campaignIndex + 1
            $powerShell = [PowerShell]::Create()
            $powerShell.RunspacePool = $pool
            $null = $powerShell.AddScript($requestScript.ToString())
            $null = $powerShell.AddArgument($httpClient)
            $null = $powerShell.AddArgument($uri)
            $null = $powerShell.AddArgument($userToken)
            $null = $powerShell.AddArgument($requestCampaignId)
            $null = $powerShell.AddArgument($index)
            $null = $powerShell.AddArgument($userSlot)
            $null = $powerShell.AddArgument($campaignSlot)
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
        [System.Net.Http.HttpMethod]::Post, "$campaignBaseUrl/api/draws")
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
    if ($ordered.Count -eq 0) { return $null }
    $index = [Math]::Max(0, [Math]::Ceiling($percentile * $ordered.Count) - 1)
    return [Math]::Round($ordered[$index], 2)
}

function Get-K6MetricValue([object]$Summary, [string]$MetricName, [string]$ValueName) {
    $metric = $Summary.metrics.PSObject.Properties[$MetricName]
    if (-not $metric) { return $null }
    $value = $metric.Value.values.PSObject.Properties[$ValueName]
    if (-not $value) { return $null }
    return $value.Value
}

function Convert-DockerMemoryToBytes([string]$Value) {
    if ($Value -notmatch '^\s*(?<amount>\d+(?:\.\d+)?)\s*(?<unit>B|kB|KB|KiB|MB|MiB|GB|GiB|TB|TiB)\s*$') { return $null }
    $amount = [double]::Parse($Matches.amount, [Globalization.CultureInfo]::InvariantCulture)
    switch -Regex ($Matches.unit) {
        '^(B)$' { return $amount }
        '^(kB|KB)$' { return $amount * 1000 }
        '^(KiB)$' { return $amount * 1024 }
        '^(MB)$' { return $amount * 1000000 }
        '^(MiB)$' { return $amount * 1MB }
        '^(GB)$' { return $amount * 1000000000 }
        '^(GiB)$' { return $amount * 1GB }
        '^(TB)$' { return $amount * 1000000000000 }
        '^(TiB)$' { return $amount * 1TB }
    }
    return $null
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
    $campaignDbArgs += @('--detach', '--name', $campaignDb, '--publish', "127.0.0.1:$($campaignDbPort):3306",
        '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_DATABASE', '--env', 'MYSQL_USER', '--env', 'MYSQL_PASSWORD', 'mysql:8.4')
    & docker @campaignDbArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Campaign MySQL container.' }
    $startedContainers.Add($campaignDb)

    $env:MYSQL_ROOT_PASSWORD = $rootPassword
    $env:MYSQL_DATABASE = 'prizeflux_worker'
    $env:MYSQL_USER = 'prizeflux_worker'
    $env:MYSQL_PASSWORD = $workerPassword
    & docker run --rm --detach --name $workerDb --publish "127.0.0.1:$($workerDbPort):3306" `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD mysql:8.4 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Worker MySQL container.' }
    $startedContainers.Add($workerDb)
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Wait-MySql $campaignDb
    Wait-MySql $workerDb

    if ($queueArrivalBenchmark) {
        $mysqlLockObserverPassword = New-Secret
        $observerSql = @"
CREATE USER 'pfx_lock_observer'@'%' IDENTIFIED BY '$mysqlLockObserverPassword';
GRANT SELECT ON performance_schema.data_lock_waits TO 'pfx_lock_observer'@'%';
"@
        $env:MYSQL_PWD = $rootPassword
        try {
            $observerSql | & docker exec --interactive --env MYSQL_PWD $campaignDb `
                mysql --user=root --batch --skip-column-names | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not provision the temporary least-privilege MySQL lock observer.' }
        } finally {
            [Environment]::SetEnvironmentVariable('MYSQL_PWD', $null, 'Process')
        }
        $env:PFX_MYSQL_LOCK_METRICS_ENABLED = 'true'
        $env:PFX_MYSQL_LOCK_METRICS_JDBC_URL = "jdbc:mysql://127.0.0.1:$campaignDbPort/"
        $env:PFX_MYSQL_LOCK_METRICS_USERNAME = 'pfx_lock_observer'
        $env:PFX_MYSQL_LOCK_METRICS_PASSWORD = $mysqlLockObserverPassword
        $env:PFX_MYSQL_LOCK_METRICS_SAMPLE_DELAY_MS = '5000'
    }

    if ($EnableRedisLimiter) {
        $redisArgs = @('run', '--rm', '--detach', '--name', $redis, '--publish', "127.0.0.1:$($redisPort):6379", 'redis:7.4-alpine')
        & docker @redisArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable Redis container.' }
        $startedContainers.Add($redis)
        Wait-Redis $redis
    }

    # Single-node KRaft broker, advertised separately for in-container and host clients.
    $kafkaArgs = @(
        'run', '--rm', '--detach', '--name', $kafka, '--publish', "127.0.0.1:$($kafkaPort):9094",
        '--env', 'CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk',
        '--env', 'KAFKA_NODE_ID=1',
        '--env', 'KAFKA_PROCESS_ROLES=broker,controller',
        '--env', 'KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:9094,CONTROLLER://:9093',
        '--env', "KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:9092,EXTERNAL://localhost:$kafkaPort",
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
    Wait-TcpService '127.0.0.1' $kafkaPort 120

    $env:PFX_DB_URL = "jdbc:mysql://127.0.0.1:$campaignDbPort/prizeflux"
    $env:PFX_DB_USER = 'prizeflux'
    $env:PFX_DB_PASSWORD = $campaignPassword
    $env:PFX_DB_CONNECTION_TIMEOUT_MS = '3000'
    $env:PFX_JWT_SECRET = $jwtSecret
    $env:PRIZEFLUX_BOOTSTRAP_OPERATOR_ENABLED = 'true'
    $env:PFX_BOOTSTRAP_OPERATOR_USERNAME = $operatorUsername
    $env:PFX_BOOTSTRAP_OPERATOR_PASSWORD = $operatorPassword
    $env:KAFKA_BOOTSTRAP_SERVERS = "localhost:$kafkaPort"
    $env:PFX_DRAW_RATE_LIMIT_ENABLED = [string]$EnableRedisLimiter.IsPresent.ToString().ToLowerInvariant()
    $env:PFX_DRAW_RATE_LIMIT_MAX_REQUESTS = [string]($RequestCount + 5)
    $env:PFX_DRAW_RATE_LIMIT_WINDOW_SECONDS = '3600'
    if ($InjectDrawQueueKafkaOutage) {
        $env:PFX_DRAW_QUEUE_CONSUMER_ENABLED = 'false'
        # Disable periodic MySQL fallback during this bounded outage window so the test
        # proves recovery via the durable Outbox notification and Kafka consumer.
        $env:PFX_DRAW_QUEUE_SCAN_INITIAL_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_SCAN_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_PROTECTION_ENABLED = 'false'
    }
    if ($InjectDrawQueueKafkaRebalance) {
        $env:PFX_DRAW_QUEUE_CONSUMER_ENABLED = 'true'
        $env:PFX_DRAW_QUEUE_SCAN_INITIAL_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_SCAN_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_PROTECTION_ENABLED = 'false'
    }
    if ($BenchmarkDrawQueue) {
        $maxRequestsPerUser = [int][Math]::Ceiling($scenarioRequestCount / [double]$QueueBenchmarkUserCount)
        $maxRequestsPerCampaign = [int][Math]::Ceiling($scenarioRequestCount / [double]$QueueBenchmarkCampaignCount)
        $redisUserRequestLimit = $maxRequestsPerUser + 5
        $redisCampaignRequestLimit = $maxRequestsPerCampaign + 5
        $env:PFX_DRAW_QUEUE_CONSUMER_ENABLED = if ($queueArrivalBenchmark) { 'true' } else { 'false' }
        $env:PFX_DRAW_QUEUE_CONCURRENCY = [string]$QueueConsumerConcurrency
        # A continuous-arrival run keeps the Kafka consumer active. Delayed recovery
        # scans remain useful to isolate the Kafka path during the short load window.
        $env:PFX_DRAW_QUEUE_SCAN_INITIAL_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_SCAN_DELAY_MS = '600000'
        $env:PFX_DRAW_QUEUE_PROTECTION_ENABLED = 'true'
        $env:PFX_DRAW_QUEUE_RATE_WINDOW_SECONDS = '3600'
        $env:PFX_DRAW_QUEUE_USER_MAX_REQUESTS = [string]$redisUserRequestLimit
        $env:PFX_DRAW_QUEUE_CAMPAIGN_MAX_REQUESTS = [string]$redisCampaignRequestLimit
        $env:PFX_DRAW_QUEUE_GLOBAL_MAX_REQUESTS = [string]($scenarioRequestCount + 5)
        $effectiveQueueBacklogLimit = if ($QueueBacklogLimit -gt 0) { $QueueBacklogLimit } else { $scenarioRequestCount + 50 }
        $env:PFX_DRAW_QUEUE_MAX_BACKLOG = [string]$effectiveQueueBacklogLimit
        $env:PFX_DRAW_QUEUE_MAX_OLDEST_AGE_SECONDS = '3600'
        $env:PFX_DRAW_QUEUE_DB_POOL_HIGH_WATERMARK = '1.0'
    }
    if ($relayCandidateFault) {
        $env:PFX_DRAW_QUEUE_CONCURRENCY = [string]$QueueConsumerConcurrency
    }
    if ($EnableRedisLimiter) {
        $env:PFX_REDIS_HOST = '127.0.0.1'
        $env:PFX_REDIS_PORT = [string]$redisPort
    }
    $campaignArgs = @('--spring.profiles.active=local-full', "--server.port=$campaignPort")
    if ($relayConfigurationOverrideAllowed) {
        $campaignArgs += "--prizeflux.outbox.relay.fixed-delay=${effectiveOutboxRelayDelayMilliseconds}"
        $campaignArgs += "--prizeflux.outbox.relay.parallelism=$OutboxRelayParallelism"
    }
    if ($hikariConfigurationOverrideAllowed) {
        $campaignArgs += "--spring.datasource.hikari.maximum-pool-size=$QueueBenchmarkHikariMaximumPoolSize"
    }
    if ($InjectDrawQueueKafkaOutage) {
        # Start API/outbox without a consumer. The test later starts a fresh consumer
        # group only after the notification is already present in Kafka.
        $campaignArgs += @('--prizeflux.kafka.draw-queue-consumer-enabled=false',
            '--prizeflux.kafka.draw-queue-scan-initial-delay-ms=600000',
            '--prizeflux.kafka.draw-queue-scan-delay-ms=600000',
            '--prizeflux.traffic.draw-queue.enabled=false')
    }
    $bootstrap = Start-ServiceProcess $campaignJarPath $logs.bootstrapOut $logs.bootstrapErr $campaignArgs
    $null = Wait-HttpHealth "$campaignBaseUrl/api/health" $bootstrap @($logs.bootstrapOut, $logs.bootstrapErr)
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
    $null = Wait-HttpHealth "$campaignBaseUrl/api/health" $campaign @($logs.campaignOut, $logs.campaignErr)

    $env:PFX_WORKER_DB_URL = "jdbc:mysql://127.0.0.1:$workerDbPort/prizeflux_worker"
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
    $operator = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/auth/login" `
        -ContentType 'application/json' -Body $operatorLogin
    $operatorHeaders = @{ Authorization = "Bearer $($operator.data.token)" }

    # A fixed test-only BCrypt hash authenticates disposable USER identities; no account or password is reused.
    $benchmarkUsernames = [System.Collections.Generic.List[string]]::new()
    $userRows = [System.Collections.Generic.List[string]]::new()
    $userPasswordHash = '$2a$10$nZsGVyRD3UDUZatvdP.ZuexcAQ81RRw5KsSbvLkSEB47rl4Wb6ms.'
    for ($userIndex = 1; $userIndex -le $QueueBenchmarkUserCount; $userIndex++) {
        $userSuffix = $userIndex.ToString('D3', [Globalization.CultureInfo]::InvariantCulture)
        $username = "smoke.user-$tag-$userSuffix"
        $benchmarkUsernames.Add($username)
        $userRows.Add("('$username', '$userPasswordHash', 'USER', 'ACTIVE')")
    }
    $userSql = 'INSERT INTO app_user (username, password_hash, role, state) VALUES ' + ($userRows -join ', ')
    $env:MYSQL_PWD = $campaignPassword
    & docker exec --env MYSQL_PWD $campaignDb mysql --user=prizeflux --database=prizeflux --execute=$userSql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the disposable USER accounts.' }
    $userTokens = [System.Collections.Generic.List[string]]::new()
    foreach ($username in $benchmarkUsernames) {
        $userLogin = @{ username = $username; password = 'smoke-user-password' } | ConvertTo-Json -Compress
        $loginResult = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/auth/login" `
            -ContentType 'application/json' -Body $userLogin
        if ($userTokens.Count -eq 0) { $user = $loginResult }
        $userTokens.Add([string]$loginResult.data.token)
    }
    $userHeaders = @{ Authorization = "Bearer $($user.data.token)" }

    $randomId = [Convert]::ToInt64($tag.Substring(0, 8), 16) + 202609240000
    $campaignIds = [System.Collections.Generic.List[long]]::new()
    $now = [DateTimeOffset]::UtcNow
    $campaignDrawLimit = if ($InjectDrawQueueKafkaRebalance) { 24 } else { $scenarioRequestCount }
    $totalConfiguredPrizeStock = if ($InjectDrawQueueKafkaRebalance) { 24 } else { $PrizeStock }
    for ($campaignIndex = 1; $campaignIndex -le $QueueBenchmarkCampaignCount; $campaignIndex++) {
        $campaignId = $randomId + (($campaignIndex - 1) * 2)
        $prizeId = $campaignId + 1
        $campaignIds.Add([long]$campaignId)
        $campaignBody = @{
            id = $campaignId; name = "Cross-process fulfillment smoke $campaignIndex"
            startsAt = $now.AddMinutes(-2).ToString('o'); endsAt = $now.AddHours(1).ToString('o')
        } | ConvertTo-Json -Compress
        $null = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/campaigns" `
            -Headers $operatorHeaders -ContentType 'application/json' -Body $campaignBody
        $campaignPrizeStock = [int][Math]::Floor($totalConfiguredPrizeStock / [double]$QueueBenchmarkCampaignCount)
        if ($campaignIndex -le ($totalConfiguredPrizeStock % $QueueBenchmarkCampaignCount)) { $campaignPrizeStock++ }
        $configuration = @{
            dailyLimit = $campaignDrawLimit
            prizes = @(@{ id = $prizeId; name = "Benchmark prize $campaignIndex"; totalStock = $campaignPrizeStock; probability = 1.0 })
        } | ConvertTo-Json -Depth 5 -Compress
        $null = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/campaigns/$campaignId/configuration" `
            -Headers $operatorHeaders -ContentType 'application/json' -Body $configuration
        $null = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/campaigns/$campaignId/publish" `
            -Headers $operatorHeaders
    }
    $campaignId = $campaignIds[0]

    # Apply identical health, login, and campaign-read warmup in limiter-on/off runs.
    $null = Invoke-RestMethod -Uri "$campaignBaseUrl/api/health"
    $null = Invoke-RestMethod -Uri "$campaignBaseUrl/api/campaigns/$campaignId" -Headers $userHeaders
    Invoke-DrawPipelineWarmup $user.data.token $campaignId

    if ($InjectDrawQueueKafkaRebalance) {
        $firstAssignmentDeadline = [DateTime]::UtcNow.AddSeconds(120)
        $firstPartitions = @()
        $firstGeneration = 0
        while ([DateTime]::UtcNow -lt $firstAssignmentDeadline) {
            $campaign.Refresh()
            if ($campaign.HasExited) { throw 'The first Campaign consumer exited before its initial assignment.' }
            $firstPartitions = @(Get-QueueAssignedPartitions $logs.campaignOut)
            $firstGeneration = Get-QueueConsumerGeneration $logs.campaignOut
            if ($firstPartitions.Count -eq 12 -and $firstGeneration -ge 1) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($firstPartitions.Count -ne 12 -or $firstGeneration -lt 1) {
            throw "The first Campaign consumer did not own the expected 12 topic partitions (generation=$firstGeneration; assigned=$($firstPartitions.Count))."
        }

        $rebalanceArguments = @('--spring.profiles.active=local-full', "--server.port=$rebalanceCampaignPort",
            '--prizeflux.kafka.draw-queue-consumer-enabled=true',
            '--prizeflux.kafka.draw-queue-scan-initial-delay-ms=600000',
            '--prizeflux.kafka.draw-queue-scan-delay-ms=600000',
            '--prizeflux.traffic.draw-queue.enabled=false')
        $rebalanceCampaign = Start-ServiceProcess $campaignJarPath $logs.rebalanceCampaignOut `
            $logs.rebalanceCampaignErr $rebalanceArguments
        $rebalanceBaseUrl = "http://127.0.0.1:$rebalanceCampaignPort"
        $null = Wait-HttpHealth "$rebalanceBaseUrl/api/health" $rebalanceCampaign `
            @($logs.rebalanceCampaignOut, $logs.rebalanceCampaignErr)

        $rebalanceDeadline = [DateTime]::UtcNow.AddSeconds(90)
        $firstPartitionsAfterRebalance = @()
        $secondPartitionsAfterRebalance = @()
        $firstGenerationAfterRebalance = 0
        $secondGenerationAfterRebalance = 0
        while ([DateTime]::UtcNow -lt $rebalanceDeadline) {
            $campaign.Refresh()
            $rebalanceCampaign.Refresh()
            if ($campaign.HasExited -or $rebalanceCampaign.HasExited) {
                throw 'A Campaign consumer exited while the group was rebalancing.'
            }
            $firstPartitionsAfterRebalance = @(Get-QueueAssignedPartitions $logs.campaignOut)
            $secondPartitionsAfterRebalance = @(Get-QueueAssignedPartitions $logs.rebalanceCampaignOut)
            $firstGenerationAfterRebalance = Get-QueueConsumerGeneration $logs.campaignOut
            $secondGenerationAfterRebalance = Get-QueueConsumerGeneration $logs.rebalanceCampaignOut
            $allAssignedPartitions = @(($firstPartitionsAfterRebalance + $secondPartitionsAfterRebalance) | Sort-Object -Unique)
            if ($firstGenerationAfterRebalance -ge 2 -and $secondGenerationAfterRebalance -ge 2 `
                    -and $firstPartitionsAfterRebalance.Count -gt 0 -and $secondPartitionsAfterRebalance.Count -gt 0 `
                    -and $firstPartitionsAfterRebalance.Count + $secondPartitionsAfterRebalance.Count -eq 12 `
                    -and $allAssignedPartitions.Count -eq 12) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($firstGenerationAfterRebalance -lt 2 -or $secondGenerationAfterRebalance -lt 2 `
                -or $firstPartitionsAfterRebalance.Count -eq 0 -or $secondPartitionsAfterRebalance.Count -eq 0 `
                -or $firstPartitionsAfterRebalance.Count + $secondPartitionsAfterRebalance.Count -ne 12 `
                -or $allAssignedPartitions.Count -ne 12) {
            throw "Two-member Campaign consumer group did not rebalance all 12 partitions (generations=$firstGenerationAfterRebalance/$secondGenerationAfterRebalance; assignments=$($firstPartitionsAfterRebalance.Count)/$($secondPartitionsAfterRebalance.Count))."
        }

        $rebalanceRequestCount = 24
        for ($index = 1; $index -le $rebalanceRequestCount; $index++) {
            $requestKey = "reb-$tag-$index"
            $queueRequest = @{ campaignId = $campaignId; requestKey = $requestKey } | ConvertTo-Json -Compress
            $accepted = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/draw-queue" `
                -Headers $userHeaders -ContentType 'application/json' -Body $queueRequest -TimeoutSec 15
            if ($accepted.data.status -ne 'QUEUED') {
                throw "Queue request $index was not accepted during consumer rebalance verification."
            }
        }

        $queueDeadline = [DateTime]::UtcNow.AddSeconds(90)
        $queueRows = '0'; $completedRows = '0'; $drawRows = '0'; $sentQueueOutboxRows = '0'; $remainingStock = '-1'
        while ([DateTime]::UtcNow -lt $queueDeadline) {
            $queueRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id = $campaignId"
            $completedRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id = $campaignId AND status = 'COMPLETED'"
            $drawRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $campaignId"
            $sentQueueOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'DrawQueued' AND aggregate_id = $campaignId"
            $remainingStock = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id = $campaignId"
            if ($queueRows -eq [string]$rebalanceRequestCount -and $completedRows -eq [string]$rebalanceRequestCount `
                    -and $drawRows -eq [string]$rebalanceRequestCount -and $sentQueueOutboxRows -eq [string]$rebalanceRequestCount `
                    -and $remainingStock -eq '0') { break }
            Start-Sleep -Milliseconds 500
        }
        if ($queueRows -ne [string]$rebalanceRequestCount -or $completedRows -ne [string]$rebalanceRequestCount `
                -or $drawRows -ne [string]$rebalanceRequestCount -or $sentQueueOutboxRows -ne [string]$rebalanceRequestCount `
                -or $remainingStock -ne '0') {
            throw "Queue-consumer rebalance correctness failed (queue=$queueRows; completed=$completedRows; draws=$drawRows; sentOutbox=$sentQueueOutboxRows; inventory=$remainingStock)."
        }

        $report = [pscustomobject]@{
            Result = 'PASS'; Scenario = 'Draw-queue Kafka consumer group rebalance across two Campaign processes'
            Environment = [pscustomobject]@{
                OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
                JavaHome = $JavaHome; JavaVersion = (& $java -version 2>&1 | Out-String).Trim()
                DockerEngine = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
                MySqlImage = 'mysql:8.4'; Broker = 'Disposable single-node Kafka 3.9.1'
            }
            Configuration = [pscustomobject]@{
                ConsumerInstances = 2; ConsumerGroup = 'prizeflux-campaign-draw-queue'; TopicPartitions = 12
                QueueRequests = $rebalanceRequestCount; DistinctRequestKeys = $rebalanceRequestCount
                PeriodicRecoveryScanDelayed = $true; PersistentVolumes = $false
            }
            Rebalance = [pscustomobject]@{
                InitialGeneration = $firstGeneration; FinalGenerationFirstConsumer = $firstGenerationAfterRebalance
                FinalGenerationSecondConsumer = $secondGenerationAfterRebalance
                FirstConsumerPartitions = $firstPartitionsAfterRebalance
                SecondConsumerPartitions = $secondPartitionsAfterRebalance
                TotalAssignedPartitions = $allAssignedPartitions.Count
            }
            Correctness = [pscustomobject]@{
                QueueRows = [int]$queueRows; CompletedQueueRows = [int]$completedRows
                DrawRows = [int]$drawRows; SentDrawQueuedOutboxRows = [int]$sentQueueOutboxRows
                InventoryRemaining = [int]$remainingStock
            }
            CredentialsOrTokensPrinted = $false
            Note = 'Two local consumers rebalanced a single-node 12-partition topic; 24 distinct queue-request keys completed with delayed DB recovery scan. This is not multi-broker HA or capacity evidence.'
        }
        Write-BenchmarkEvidence $evidenceFullPath $report
        [pscustomobject]@{
            Result = 'PASS'; Scenario = $report.Scenario; ConsumerGenerations = @(
                $firstGenerationAfterRebalance, $secondGenerationAfterRebalance)
            PartitionsPerConsumer = @($firstPartitionsAfterRebalance.Count, $secondPartitionsAfterRebalance.Count)
            QueueRequests = $rebalanceRequestCount; CompletedQueueRows = $completedRows
            DrawRows = $drawRows; InventoryRemaining = $remainingStock
            EvidencePath = if ($evidenceFullPath) { [IO.Path]::GetRelativePath($repoRoot, $evidenceFullPath) } else { '' }
            CredentialsOrTokensPrinted = $false
        } | Format-List
        return
    }

    if ($queueArrivalBenchmark) {
        $campaignIdCsv = $campaignIds -join ','
        $consumerDeadline = [DateTime]::UtcNow.AddSeconds(120)
        $assignedPartitions = @()
        $consumerGeneration = 0
        while ([DateTime]::UtcNow -lt $consumerDeadline) {
            $campaign.Refresh(); $worker.Refresh()
            if ($campaign.HasExited) { throw 'Campaign exited before the continuous-arrival run.' }
            if ($worker.HasExited) { throw 'Worker exited before the continuous-arrival run.' }
            $assignedPartitions = @(Get-QueueAssignedPartitionUnion $logs.campaignOut $QueueConsumerConcurrency)
            $consumerGeneration = Get-QueueConsumerGeneration $logs.campaignOut
            if ($assignedPartitions.Count -eq 12 -and $consumerGeneration -ge 1) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($assignedPartitions.Count -ne 12 -or $consumerGeneration -lt 1) {
            throw "The active queue consumer did not acquire the expected 12 partitions (generation=$consumerGeneration; assigned=$($assignedPartitions.Count))."
        }

        $tokenJson = ConvertTo-Json -InputObject ([string[]]$userTokens.ToArray()) -Compress
        Set-Content -LiteralPath $logs.k6Users -Value $tokenJson -Encoding utf8NoBOM
        $env:PFX_K6_BASE_URL = $campaignBaseUrl
        $env:PFX_K6_USERS_FILE = $logs.k6Users
        $env:PFX_K6_CAMPAIGN_IDS = $campaignIdCsv
        $env:PFX_K6_RUN_PREFIX = "pfx-$tag-"
        $env:PFX_K6_RATE = [string]$QueueArrivalRate
        $env:PFX_K6_DURATION_SECONDS = [string]$QueueArrivalDurationSeconds
        $env:PFX_K6_SUMMARY_PATH = $k6SummaryFullPath

        $k6ScriptPath = Join-Path $repoRoot 'scripts/perf/draw-queue-arrival-rate.js'
        $k6Arguments = @('run', '--quiet')
        foreach ($name in @('PFX_K6_BASE_URL', 'PFX_K6_USERS_FILE', 'PFX_K6_CAMPAIGN_IDS',
                'PFX_K6_RUN_PREFIX', 'PFX_K6_RATE', 'PFX_K6_DURATION_SECONDS', 'PFX_K6_SUMMARY_PATH')) {
            $k6Arguments += @('--env', "$name=$([Environment]::GetEnvironmentVariable($name, 'Process'))")
        }
        $k6Arguments += $k6ScriptPath
        $k6Process = Start-Process -FilePath $k6ExecutablePath `
            -ArgumentList $k6Arguments -WorkingDirectory $repoRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $logs.k6Run -RedirectStandardError $logs.k6Err
        $processIds.Add($k6Process.Id)
        $resourceSamples = [System.Collections.Generic.List[object]]::new()
        $oldestQueueAgeSamples = [System.Collections.Generic.List[object]]::new()
        $campaignRuntimeMetricSamples = [System.Collections.Generic.List[object]]::new()
        $hostProcessCpuSamples = [System.Collections.Generic.List[object]]::new()
        $k6Watch = [Diagnostics.Stopwatch]::StartNew()
        $nextResourceSampleSeconds = 5
        $previousCpuSampleSeconds = $null
        $previousCampaignCpuSeconds = $null
        $previousWorkerCpuSeconds = $null
        $memoryBreachStartedAt = @{}
        $memoryThresholdExceeded = $false
        while ($true) {
            $k6Process.Refresh()
            if ($k6Process.HasExited) { break }
            $campaign.Refresh(); $worker.Refresh()
            if ($campaign.HasExited -or $worker.HasExited) {
                Stop-Process -Id $k6Process.Id -Force -ErrorAction SilentlyContinue
                throw 'Campaign or Worker exited during the continuous-arrival run.'
            }
            if ($k6Watch.Elapsed.TotalSeconds -ge $nextResourceSampleSeconds) {
                $queueStateSql = "SELECT CONCAT(COUNT(*), '|', COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0)) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND status IN ('QUEUED','PROCESSING','RETRY_WAIT','MANUAL_REVIEW')"
                $queueState = [string](Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' $queueStateSql)
                $queueStateMatch = [regex]::Match($queueState, '^(?<pending>\d+)\|(?<age>\d+)$')
                if (-not $queueStateMatch.Success) { throw 'Could not parse isolated draw queue backlog sample.' }
                $pendingQueueRequests = [int]$queueStateMatch.Groups['pending'].Value
                $oldestAge = [int]$queueStateMatch.Groups['age'].Value
                $oldestQueueAgeSamples.Add([pscustomobject]@{
                    ElapsedSeconds = [Math]::Round($k6Watch.Elapsed.TotalSeconds, 1)
                    PendingActiveRequests = $pendingQueueRequests; OldestActiveItemSeconds = $oldestAge
                })
                $campaignRuntimeMetricSamples.Add((Get-CampaignRuntimeMetricsSample `
                    $k6Watch.Elapsed.TotalSeconds $operatorHeaders $campaignBaseUrl))
                $cpuElapsedSeconds = $k6Watch.Elapsed.TotalSeconds
                $campaignCpuSeconds = $null; $workerCpuSeconds = $null
                $campaignCpuPercentOfOneCore = $null; $workerCpuPercentOfOneCore = $null
                try {
                    $campaign.Refresh(); $worker.Refresh()
                    $campaignCpuSeconds = [double]$campaign.CPU
                    $workerCpuSeconds = [double]$worker.CPU
                    if ($null -ne $previousCpuSampleSeconds) {
                        $cpuWindowSeconds = $cpuElapsedSeconds - $previousCpuSampleSeconds
                        if ($cpuWindowSeconds -gt 0 -and $campaignCpuSeconds -ge $previousCampaignCpuSeconds) {
                            $campaignCpuPercentOfOneCore = [Math]::Round(
                                100 * ($campaignCpuSeconds - $previousCampaignCpuSeconds) / $cpuWindowSeconds, 2)
                        }
                        if ($cpuWindowSeconds -gt 0 -and $workerCpuSeconds -ge $previousWorkerCpuSeconds) {
                            $workerCpuPercentOfOneCore = [Math]::Round(
                                100 * ($workerCpuSeconds - $previousWorkerCpuSeconds) / $cpuWindowSeconds, 2)
                        }
                    }
                } catch {
                    $campaignCpuSeconds = $null; $workerCpuSeconds = $null
                }
                $hostProcessCpuSamples.Add([pscustomobject]@{
                    ElapsedSeconds = [Math]::Round($cpuElapsedSeconds, 1)
                    CampaignJvmCpuPercentOfOneCore = $campaignCpuPercentOfOneCore
                    WorkerJvmCpuPercentOfOneCore = $workerCpuPercentOfOneCore
                })
                if ($null -ne $campaignCpuSeconds -and $null -ne $workerCpuSeconds) {
                    $previousCpuSampleSeconds = $cpuElapsedSeconds
                    $previousCampaignCpuSeconds = $campaignCpuSeconds
                    $previousWorkerCpuSeconds = $workerCpuSeconds
                }
                $statsLines = @(& docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' `
                    $campaignDb $workerDb $kafka $redis 2>$null)
                $stats = foreach ($line in $statsLines) {
                    $parts = ([string]$line).Split('|', 3)
                    if ($parts.Count -eq 3) {
                        $memoryParts = $parts[2].Split('/', 2)
                        $memoryPercent = $null
                        if ($memoryParts.Count -eq 2) {
                            $usedBytes = Convert-DockerMemoryToBytes $memoryParts[0]
                            $limitBytes = Convert-DockerMemoryToBytes $memoryParts[1]
                            if ($null -ne $usedBytes -and $null -ne $limitBytes -and $limitBytes -gt 0) {
                                $memoryPercent = [Math]::Round($usedBytes / $limitBytes * 100, 2)
                            }
                        }
                        [pscustomobject]@{
                            Container = $parts[0]; CpuPercent = $parts[1]; MemoryUsage = $parts[2]
                            MemoryPercentOfReportedLimit = $memoryPercent
                        }
                    }
                }
                $resourceSamples.Add([pscustomobject]@{
                    ElapsedSeconds = [Math]::Round($k6Watch.Elapsed.TotalSeconds, 1)
                    Containers = @($stats)
                })
                $highMemoryContainers = @($stats | Where-Object {
                    $null -ne $_.MemoryPercentOfReportedLimit -and $_.MemoryPercentOfReportedLimit -ge 85
                } | ForEach-Object Container)
                foreach ($containerName in @($memoryBreachStartedAt.Keys)) {
                    if ($highMemoryContainers -notcontains $containerName) { $memoryBreachStartedAt.Remove($containerName) }
                }
                foreach ($containerName in $highMemoryContainers) {
                    if (-not $memoryBreachStartedAt.ContainsKey($containerName)) {
                        $memoryBreachStartedAt[$containerName] = [DateTimeOffset]::UtcNow
                    } elseif (([DateTimeOffset]::UtcNow - $memoryBreachStartedAt[$containerName]).TotalSeconds -ge 30) {
                        $memoryThresholdExceeded = $true
                    }
                }
                $nextResourceSampleSeconds += 5
            }
            Start-Sleep -Milliseconds 250
        }
        $k6Process.WaitForExit()
        $k6ExitCode = [int]$k6Process.ExitCode
        $processIds.Remove($k6Process.Id) | Out-Null
        $k6Watch.Stop()
        if (-not (Test-Path -LiteralPath $k6SummaryFullPath -PathType Leaf)) {
            $k6Output = @((Get-Content -LiteralPath $logs.k6Run -Tail 12 -ErrorAction SilentlyContinue) +
                (Get-Content -LiteralPath $logs.k6Err -Tail 12 -ErrorAction SilentlyContinue)) -join "`n"
            throw "k6 exited with code $k6ExitCode without writing its summary. Output: $k6Output"
        }
        $k6Summary = Get-Content -LiteralPath $k6SummaryFullPath -Raw | ConvertFrom-Json
        $metricNames = @('draw_queue_attempts', 'draw_queue_accepted', 'draw_queue_capacity_rejections',
            'draw_queue_client_errors', 'draw_queue_server_errors', 'draw_queue_transport_errors',
            'draw_queue_unexpected_responses', 'draw_queue_accepted_rate', 'draw_queue_capacity_rejection_rate',
            'draw_queue_unexpected_rate', 'draw_queue_accepted_latency', 'draw_queue_warmup_attempts',
            'draw_queue_warmup_accepted', 'draw_queue_warmup_unexpected', 'draw_queue_warmup_accepted_latency',
            'dropped_iterations')
        $k6Values = @{}
        foreach ($metricName in $metricNames) {
            foreach ($valueName in @('count', 'rate', 'p(95)', 'p(99)')) {
                $metricValue = Get-K6MetricValue $k6Summary $metricName $valueName
                if ($null -ne $metricValue) { $k6Values["$metricName.$valueName"] = $metricValue }
            }
        }
        foreach ($requiredMetric in @('draw_queue_attempts.count', 'draw_queue_accepted.count',
                'draw_queue_warmup_attempts.count', 'draw_queue_warmup_accepted.count', 'dropped_iterations.count')) {
            if (-not $k6Values.ContainsKey($requiredMetric)) { throw "k6 summary is missing the required metric '$requiredMetric'." }
        }

        $k6Attempts = [int]$k6Values['draw_queue_attempts.count']
        $k6Accepted = [int]$k6Values['draw_queue_accepted.count']
        $k6ProtectedRejections = [int]$k6Values['draw_queue_capacity_rejections.count']
        $k6ClientErrors = [int]$k6Values['draw_queue_client_errors.count']
        $k6ServerErrors = [int]$k6Values['draw_queue_server_errors.count']
        $k6TransportErrors = [int]$k6Values['draw_queue_transport_errors.count']
        $k6Unexpected = [int]$k6Values['draw_queue_unexpected_responses.count']
        $k6WarmupAttempts = [int]$k6Values['draw_queue_warmup_attempts.count']
        $k6WarmupAccepted = [int]$k6Values['draw_queue_warmup_accepted.count']
        $k6WarmupUnexpected = [int]$k6Values['draw_queue_warmup_unexpected.count']
        $k6DroppedIterations = [int]$k6Values['dropped_iterations.count']
        $measuredAcceptanceRate = if ($k6Attempts -gt 0) { $k6Accepted / [double]$k6Attempts } else { 0 }
        $unexpectedErrorRate = if ($k6Attempts -gt 0) { ($k6ClientErrors + $k6ServerErrors + $k6TransportErrors) / [double]$k6Attempts } else { 0 }
        $expectedQueueRows = $k6Accepted + $k6WarmupAccepted

        $campaignSnapshotSql = @"
SELECT
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%'),
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND status = 'COMPLETED'),
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND status = 'REJECTED'),
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND status IN ('QUEUED','PROCESSING','RETRY_WAIT','MANUAL_REVIEW')),
 (SELECT COUNT(*) FROM draw_result WHERE id IN (SELECT draw_result_id FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%')),
 (SELECT COUNT(*) FROM draw_result WHERE outcome = 'WON' AND id IN (SELECT draw_result_id FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%')),
 (SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'DrawQueued' AND aggregate_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'PrizeFulfillmentRequested' AND aggregate_id IN (SELECT draw_result_id FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%')),
 (SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND status IN ('QUEUED','PROCESSING','RETRY_WAIT','MANUAL_REVIEW'))
"@ -replace '[\r\n]+', ' '
        $workerSnapshotSql = @"
SELECT
 (SELECT COUNT(*) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv) AND status = 'SUCCEEDED'),
 (SELECT COALESCE(SUM(attempt_count), 0) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM fulfillment_compensation_task task JOIN fulfillment f ON f.id = task.fulfillment_id WHERE f.campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM (SELECT draw_id FROM fulfillment WHERE campaign_id IN ($campaignIdCsv) GROUP BY draw_id HAVING COUNT(*) > 1) duplicate_rows)
"@ -replace '[\r\n]+', ' '

        $drainDeadline = [DateTime]::UtcNow.AddSeconds(120)
        $queueSnapshot = @(); $workerSnapshot = @()
        $drainStopwatch = [Diagnostics.Stopwatch]::StartNew()
        $nextDrainAgeSampleSeconds = 5
        do {
            $campaign.Refresh(); $worker.Refresh()
            if ($campaign.HasExited -or $worker.HasExited) { break }
            $queueSnapshot = (Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' $campaignSnapshotSql) -split "`t"
            $workerSnapshot = (Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' $workerSnapshotSql) -split "`t"
            if ($queueSnapshot.Count -eq 10 -and $workerSnapshot.Count -eq 5) {
                $queueTotal = [int]$queueSnapshot[0]; $completedTotal = [int]$queueSnapshot[1]
                $rejectedTotal = [int]$queueSnapshot[2]; $pendingTotal = [int]$queueSnapshot[3]
                $drawTotal = [int]$queueSnapshot[4]; $winnerTotal = [int]$queueSnapshot[5]
                $outboxQueueTotal = [int]$queueSnapshot[7]; $outboxFulfillmentTotal = [int]$queueSnapshot[8]
                if ($drainStopwatch.Elapsed.TotalSeconds -ge $nextDrainAgeSampleSeconds) {
                    $oldestQueueAgeSamples.Add([pscustomobject]@{
                        ElapsedSeconds = [Math]::Round($k6Watch.Elapsed.TotalSeconds, 1)
                        PendingActiveRequests = [int]$queueSnapshot[3]
                        OldestActiveItemSeconds = [int]$queueSnapshot[9]
                    })
                    $nextDrainAgeSampleSeconds += 5
                }
                if ($pendingTotal -eq 0 -and $completedTotal + $rejectedTotal -eq $queueTotal `
                        -and $drawTotal -eq $completedTotal -and $outboxQueueTotal -eq $queueTotal `
                        -and $outboxFulfillmentTotal -eq $winnerTotal `
                        -and [int]$workerSnapshot[0] -eq $winnerTotal -and [int]$workerSnapshot[1] -eq $winnerTotal) { break }
            }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $drainDeadline)
        $drainStopwatch.Stop()

        $terminalLatencyMs = @()
        $queueRows = 0; $completedRows = 0; $rejectedRows = 0; $pendingRows = 0
        $drawRows = 0; $winnerRows = 0; $inventoryRemaining = -1
        $sentQueueOutboxRows = 0; $sentFulfillmentOutboxRows = 0
        $workerRows = 0; $workerSucceededRows = 0; $workerAttemptCount = 0
        $manualReviewTasks = 0; $duplicateFulfillments = 0
        if ($queueSnapshot.Count -eq 10 -and $workerSnapshot.Count -eq 5) {
            $queueRows = [int]$queueSnapshot[0]; $completedRows = [int]$queueSnapshot[1]
            $rejectedRows = [int]$queueSnapshot[2]; $pendingRows = [int]$queueSnapshot[3]
            $drawRows = [int]$queueSnapshot[4]; $winnerRows = [int]$queueSnapshot[5]
            $inventoryRemaining = [int]$queueSnapshot[6]; $sentQueueOutboxRows = [int]$queueSnapshot[7]
            $sentFulfillmentOutboxRows = [int]$queueSnapshot[8]
            $workerRows = [int]$workerSnapshot[0]; $workerSucceededRows = [int]$workerSnapshot[1]
            $workerAttemptCount = [int]$workerSnapshot[2]; $manualReviewTasks = [int]$workerSnapshot[3]
            $duplicateFulfillments = [int]$workerSnapshot[4]
            $terminalLatencySql = "SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, created_at, completed_at) / 1000.0, 2) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND request_key LIKE 'pfx-$tag-%' AND completed_at IS NOT NULL ORDER BY created_at"
            $terminalLatencyOutput = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' $terminalLatencySql
            $terminalLatencyMs = @($terminalLatencyOutput -split "`r?`n" | Where-Object { $_ -match '^\d+(\.\d+)?$' } |
                ForEach-Object { [double]::Parse($_, [Globalization.CultureInfo]::InvariantCulture) })
        }
        $expectedWinners = [Math]::Min($PrizeStock, $completedRows)
        $maxObservedQueueAgeSeconds = if ($oldestQueueAgeSamples.Count -gt 0) {
            [int](($oldestQueueAgeSamples | Measure-Object -Property OldestActiveItemSeconds -Maximum).Maximum)
        } else { 0 }
        $maxObservedPendingQueueRequests = if ($oldestQueueAgeSamples.Count -gt 0) {
            [int](($oldestQueueAgeSamples | Measure-Object -Property PendingActiveRequests -Maximum).Maximum)
        } else { 0 }
        $allMemorySamples = @($resourceSamples | ForEach-Object { $_.Containers } | Where-Object { $null -ne $_.MemoryPercentOfReportedLimit })
        $maxObservedMemoryPercent = if ($allMemorySamples.Count -gt 0) {
            [double](($allMemorySamples | Measure-Object -Property MemoryPercentOfReportedLimit -Maximum).Maximum)
        } else { $null }
        $validRuntimeSamples = @($campaignRuntimeMetricSamples | Where-Object { $_.SampleStatus -eq 'OK' })
        $maxHikariActiveConnections = if ($validRuntimeSamples.Count -gt 0) {
            [double](($validRuntimeSamples | Measure-Object -Property HikariActiveConnections -Maximum).Maximum)
        } else { $null }
        $maxHikariPendingConnections = if ($validRuntimeSamples.Count -gt 0) {
            [double](($validRuntimeSamples | Measure-Object -Property HikariPendingConnections -Maximum).Maximum)
        } else { $null }
        $maxObservedMysqlLockWaitRelationships = if ($validRuntimeSamples.Count -gt 0) {
            [double](($validRuntimeSamples | Measure-Object -Property MySqlLockWaitRelationships -Maximum).Maximum)
        } else { $null }
        $runtimeMetricScrapeFailures = @($campaignRuntimeMetricSamples | Where-Object { $_.SampleStatus -ne 'OK' }).Count
        $correctnessPassed = $queueRows -eq $expectedQueueRows -and $pendingRows -eq 0 -and $rejectedRows -eq 0 `
            -and $completedRows -eq $queueRows -and $drawRows -eq $completedRows `
            -and $sentQueueOutboxRows -eq $queueRows -and $sentFulfillmentOutboxRows -eq $expectedWinners `
            -and $inventoryRemaining -eq ($PrizeStock - $expectedWinners) `
            -and $workerRows -eq $expectedWinners -and $workerSucceededRows -eq $expectedWinners `
            -and $workerAttemptCount -eq $expectedWinners -and $manualReviewTasks -eq 0 -and $duplicateFulfillments -eq 0
        $acceptanceP95 = $k6Values['draw_queue_accepted_latency.p(95)']
        $acceptanceP99 = $k6Values['draw_queue_accepted_latency.p(99)']
        $terminalP95 = Get-Percentile ([double[]]$terminalLatencyMs) 0.95
        $performancePassed = $k6ExitCode -eq 0 -and $k6Attempts -ge $measuredRequestCount `
            -and $k6Attempts -le ($measuredRequestCount + 1) `
            -and $k6WarmupAttempts -ge $warmupRequestCount -and $k6WarmupAttempts -le ($warmupRequestCount + 1) `
            -and $k6DroppedIterations -eq 0 `
            -and $measuredAcceptanceRate -ge 0.99 -and $k6ProtectedRejections / [double][Math]::Max(1, $k6Attempts) -le 0.01 `
            -and $unexpectedErrorRate -le 0.001 -and $null -ne $acceptanceP95 -and [double]$acceptanceP95 -le 300 `
            -and $null -ne $acceptanceP99 -and [double]$acceptanceP99 -le 1000 `
            -and $null -ne $terminalP95 -and [double]$terminalP95 -le 5000 `
            -and $maxObservedQueueAgeSeconds -lt 60 -and -not $memoryThresholdExceeded
        $drainPassed = $drainStopwatch.Elapsed.TotalSeconds -le 120 -and $pendingRows -eq 0 `
            -and $queueRows -eq $expectedQueueRows
        $passed = $performancePassed -and $correctnessPassed -and $drainPassed
        $arrivalProtectionReasons = Get-DrawQueueProtectionReasonCounts @($logs.campaignOut, $logs.campaignErr)
        $report = [pscustomobject]@{
            Result = if ($passed) { 'PASS' } else { 'FAIL' }
            Scenario = 'Open-model constant arrival rate for the asynchronous draw queue'
            Artifacts = $benchmarkArtifacts
            Environment = [pscustomobject]@{
                OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
                JavaHome = $JavaHome; JavaVersion = (& $java -version 2>&1 | Out-String).Trim()
                K6Version = (& $k6ExecutablePath version 2>&1 | Out-String).Trim()
                ProcessorCount = [Environment]::ProcessorCount
                DockerEngine = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
                MySqlImage = 'mysql:8.4'; RedisImage = 'redis:7.4-alpine'
                Broker = 'Disposable single-node Kafka 3.9.1'; FulfillmentMode = 'SIMULATED'
                LoadGeneratorAndServicesOnSameHost = $true
            }
            Configuration = [pscustomobject]@{
                MeasuredRatePerSecond = $QueueArrivalRate; MeasuredDurationSeconds = $QueueArrivalDurationSeconds
                PlannedMeasuredRequests = $measuredRequestCount; WarmupRatePerSecond = [Math]::Min(5, $QueueArrivalRate)
                WarmupDurationSeconds = 30; PlannedWarmupRequests = $warmupRequestCount
                AllowedK6BoundaryIterationPerPhase = 1
                AdditionalTestQuotaForBoundaryIterations = $arrivalBoundaryAllowance
                Users = $QueueBenchmarkUserCount; Campaigns = $QueueBenchmarkCampaignCount
                PrizeStock = $PrizeStock; QueueConsumerInstances = 1
                QueueConsumerConcurrencyPerInstance = $QueueConsumerConcurrency
                QueueConsumerGeneration = $consumerGeneration
                CampaignHikariMaximumPoolSize = $QueueBenchmarkHikariMaximumPoolSize
                QueueTopicPartitions = 12; QueueConsumerStayedOnlineDuringLoad = $true
                OutboxRelayBatchSize = 50
                OutboxRelayParallelism = $OutboxRelayParallelism
                OutboxRelayClaimLimit = $(if ($OutboxRelayParallelism -eq 1) {50} else {$OutboxRelayParallelism})
                OutboxRelayFixedDelaySeconds = $effectiveOutboxRelayDelayMilliseconds / 1000.0
                OutboxRelayFixedDelayMilliseconds = $effectiveOutboxRelayDelayMilliseconds
                PeriodicRecoveryScanDelayed = $true; PersistentVolumes = $false
            }
            Metrics = [pscustomobject]@{
                K6ExitCode = $k6ExitCode; MeasuredRequestsAttempted = $k6Attempts
                MeasuredRequestsAccepted = $k6Accepted; WarmupRequestsAttempted = $k6WarmupAttempts
                WarmupRequestsAccepted = $k6WarmupAccepted; ProtectedCapacityRejections = $k6ProtectedRejections
                ProtectionRejectionReasonCounts = $arrivalProtectionReasons
                ProtectionReasonSamplingNote = 'Server log counts span setup, warmup and measured phases; not automatically attributable to the measured phase. Missing reasons remain unverified. HTTP measured totals are separate.'
                UnexpectedResponses = $k6Unexpected; ClientErrors = $k6ClientErrors
                ServerErrors = $k6ServerErrors; TransportErrors = $k6TransportErrors
                GeneratorDroppedIterations = $k6DroppedIterations
                ActualAttemptRatePerSecond = [Math]::Round($k6Attempts / [double]$QueueArrivalDurationSeconds, 2)
                AcceptedRate = [Math]::Round($measuredAcceptanceRate, 5)
                UnexpectedErrorRate = [Math]::Round($unexpectedErrorRate, 5)
                AdmissionP95Ms = $acceptanceP95; AdmissionP99Ms = $acceptanceP99
                TerminalLatencySampleCount = $terminalLatencyMs.Count
                AcceptedToTerminalP50Ms = Get-Percentile ([double[]]$terminalLatencyMs) 0.50
                AcceptedToTerminalP95Ms = $terminalP95
                DrainAfterK6ExitMs = [Math]::Round($drainStopwatch.Elapsed.TotalMilliseconds, 2)
                OldestActiveQueueAgeSamples = $oldestQueueAgeSamples.ToArray()
                ContainerResourceSamples = $resourceSamples.ToArray()
                HostProcessCpuSamples = $hostProcessCpuSamples.ToArray()
                CampaignRuntimeMetricSamples = $campaignRuntimeMetricSamples.ToArray()
                CampaignRuntimeMetricScrapeFailures = $runtimeMetricScrapeFailures
                KafkaDiagnosticSamplingNote = 'Outbox and Kafka stage timer count/sum snapshots include warmup; stage times are seconds. All native consumer client series are preserved; commit latency is a rolling Kafka statistic in milliseconds, not terminal P95. Committed group lag is cached and must be read with last-success/failure values; missing and non-finite values remain unknown.'
                MaxHikariActiveConnections = $maxHikariActiveConnections
                MaxHikariPendingConnections = $maxHikariPendingConnections
                MaxObservedMysqlLockWaitRelationships = $maxObservedMysqlLockWaitRelationships
                MaxObservedPendingQueueRequests = $maxObservedPendingQueueRequests
                MaxObservedQueueAgeSeconds = $maxObservedQueueAgeSeconds
                MaxObservedMemoryPercentOfReportedContainerLimit = $maxObservedMemoryPercent
                StopThresholdExceeded = ($maxObservedQueueAgeSeconds -ge 60 -or $memoryThresholdExceeded)
                ResourceSamplingNote = 'docker stats samples only disposable Campaign/Worker MySQL, Kafka and Redis containers. Campaign/Worker JVM CPU is sampled from Windows process cumulative CPU time and reported as percent of one logical core. Queue active row count/oldest age, Campaign Hikari gauges, bounded draw-queue processing outcome counters/timer sums, draw transaction and low-cardinality repository segment timer counts/sums, and sampled MySQL InnoDB lock-wait relationships are sampled every 5s. Lock waits are point-in-time samples and transient waits may be missed. Memory percentage uses Docker-reported limits. Same-host generator and control-query overhead are present. An exceeded stop threshold fails this bounded stage and blocks using it to justify a higher stage.'
            }
            Correctness = [pscustomobject]@{
                QueueRows = $queueRows; CompletedQueueRows = $completedRows; RejectedQueueRows = $rejectedRows
                PendingQueueRows = $pendingRows; DrawRows = $drawRows; WonResults = $winnerRows
                InventoryRemaining = $inventoryRemaining; SentDrawQueuedOutboxRows = $sentQueueOutboxRows
                SentFulfillmentOutboxRows = $sentFulfillmentOutboxRows; WorkerLedgerRows = $workerRows
                WorkerSucceededRows = $workerSucceededRows; WorkerAttemptCountTotal = $workerAttemptCount
                ManualReviewTasks = $manualReviewTasks; DuplicateDrawFulfillments = $duplicateFulfillments
            }
            Checks = [pscustomobject]@{
                PerformanceThresholdsPassed = $performancePassed; BusinessReconciliationPassed = $correctnessPassed
                DrainWithin120Seconds = $drainPassed; OverallPassed = $passed
            }
            K6SummaryPath = [IO.Path]::GetRelativePath($repoRoot, $k6SummaryFullPath)
            CredentialsOrTokensPrinted = $false
            Note = 'Single Windows host with disposable containers, one Campaign queue consumer and one Worker; synthetic tokens are held only in a temporary local file and removed during cleanup. Not production capacity or HA evidence.'
        }
        Write-BenchmarkEvidence $evidenceFullPath $report
        [pscustomobject]@{
            Result = $report.Result; RatePerSecond = $QueueArrivalRate; DurationSeconds = $QueueArrivalDurationSeconds
            Attempts = $k6Attempts; Accepted = $k6Accepted; DroppedIterations = $k6DroppedIterations
            AdmissionP95Ms = $acceptanceP95; AdmissionP99Ms = $acceptanceP99
            TerminalP95Ms = $terminalP95; DrainWithin120Seconds = $drainPassed
            CorrectnessPassed = $correctnessPassed; EvidencePath = [IO.Path]::GetRelativePath($repoRoot, $evidenceFullPath)
            K6SummaryPath = [IO.Path]::GetRelativePath($repoRoot, $k6SummaryFullPath)
        } | Format-List
        if (-not $passed) { throw 'The continuous-arrival run did not meet its performance, generator, drain, or correctness checks; failure evidence was saved.' }
        return
    }

    if ($BenchmarkDrawQueue) {
        $queueIngressStartedAt = [DateTimeOffset]::UtcNow
        $queueBatchTimer = [Diagnostics.Stopwatch]::StartNew()
        $campaignIdCsv = $campaignIds -join ', '
        $samples = @(Invoke-ConcurrentDrawQueueRequests -tokens ($userTokens.ToArray()) `
            -campaignIds ($campaignIds.ToArray()) -count $RequestCount -concurrency $Concurrency)
        $queueBatchTimer.Stop()
        $latencies = [double[]]@($samples | ForEach-Object { [double]$_.LatencyMs })
        $acceptedCount = @($samples | Where-Object {
            $_.HttpStatus -eq 202 -and $_.QueueStatus -eq 'QUEUED' -and -not [string]::IsNullOrWhiteSpace($_.QueueRequestId)
        }).Count
        $unexpectedResponses = @($samples | Where-Object {
            $_.HttpStatus -ne 202 -and -not ($_.HttpStatus -eq 503 -and $_.ErrorCode -eq 'PFX-9001')
        })
        $serviceUnavailableCount = @($samples | Where-Object {
            $_.HttpStatus -eq 503 -and $_.ErrorCode -eq 'PFX-9001'
        }).Count
        if ($samples.Count -ne $RequestCount -or $acceptedCount -lt 1 -or $unexpectedResponses.Count -gt 0) {
            $statusSummary = ($samples | Group-Object { "$($_.HttpStatus)/$($_.ErrorCode)" } |
                ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', '
            throw "Queue load admission mismatch: accepted $acceptedCount of $RequestCount; unexpected responses=$($unexpectedResponses.Count); status/code counts: $statusSummary."
        }
        if ($RequireQueueCapacityRejections -and $serviceUnavailableCount -eq 0) {
            throw 'The explicit low-backlog capacity-guard scenario produced no 503/PFX-9001 rejections.'
        }
        $protectionReasonCounts = Get-DrawQueueProtectionReasonCounts @($logs.campaignOut, $logs.campaignErr)
        $loggedProtectionRejectionCount = [int](($protectionReasonCounts.Values | Measure-Object -Sum).Sum)
        if ($loggedProtectionRejectionCount -ne $serviceUnavailableCount) {
            throw "Protection observability mismatch: HTTP had $serviceUnavailableCount service-unavailable responses but logs classify $loggedProtectionRejectionCount."
        }
        if ($RequireQueueCapacityRejections -and $protectionReasonCounts['MYSQL_BACKLOG_LIMIT'] -ne $serviceUnavailableCount) {
            throw 'The explicit low-backlog scenario did not classify every 503/PFX-9001 as MYSQL_BACKLOG_LIMIT.'
        }
        $perUserOutcomes = @($samples | Group-Object UserSlot | ForEach-Object {
            [pscustomobject]@{
                UserSlot = [int]$_.Name
                Requests = $_.Count
                QueueAdmissions = @($_.Group | Where-Object {
                    $_.HttpStatus -eq 202 -and $_.QueueStatus -eq 'QUEUED' `
                        -and -not [string]::IsNullOrWhiteSpace($_.QueueRequestId)
                }).Count
                ServiceUnavailableResponses = @($_.Group | Where-Object {
                    $_.HttpStatus -eq 503 -and $_.ErrorCode -eq 'PFX-9001'
                }).Count
            }
        } | Sort-Object UserSlot)
        $perCampaignOutcomes = @($samples | Group-Object CampaignSlot | ForEach-Object {
            [pscustomobject]@{
                CampaignSlot = [int]$_.Name
                Requests = $_.Count
                QueueAdmissions = @($_.Group | Where-Object {
                    $_.HttpStatus -eq 202 -and $_.QueueStatus -eq 'QUEUED' `
                        -and -not [string]::IsNullOrWhiteSpace($_.QueueRequestId)
                }).Count
                ServiceUnavailableResponses = @($_.Group | Where-Object {
                    $_.HttpStatus -eq 503 -and $_.ErrorCode -eq 'PFX-9001'
                }).Count
            }
        } | Sort-Object CampaignSlot)

        $queueOutboxDeadline = [DateTime]::UtcNow.AddSeconds(60)
        $queueRows = '0'; $queuedRows = '0'; $sentQueueOutboxRows = '0'
        while ([DateTime]::UtcNow -lt $queueOutboxDeadline) {
            $queueRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv)"
            $queuedRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND status = 'QUEUED'"
            $sentQueueOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'DrawQueued' AND aggregate_id IN ($campaignIdCsv)"
            if ($queueRows -eq [string]$acceptedCount -and $queuedRows -eq [string]$acceptedCount `
                    -and $sentQueueOutboxRows -eq [string]$acceptedCount) { break }
            Start-Sleep -Milliseconds 300
        }
        if ($queueRows -ne [string]$acceptedCount -or $queuedRows -ne [string]$acceptedCount `
                -or $sentQueueOutboxRows -ne [string]$acceptedCount) {
            throw "Queue backlog was not durably staged before consumer startup (rows=$queueRows; queued=$queuedRows; sentOutbox=$sentQueueOutboxRows)."
        }
        $backlogAtConsumerStart = [int]$queuedRows
        $drawRowsBeforeConsumer = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT COUNT(*) FROM draw_result WHERE campaign_id IN ($campaignIdCsv)"
        if ($drawRowsBeforeConsumer -ne '0') {
            throw 'Queue requests were processed before the load-test consumer was started.'
        }
        $outboxPublishedAt = [DateTimeOffset]::UtcNow

        Stop-Process -Id $campaign.Id -Force
        $campaign.WaitForExit()
        $processIds.Remove($campaign.Id) | Out-Null
        $env:PFX_DRAW_QUEUE_CONSUMER_ENABLED = 'true'
        $consumerArgs = @('--spring.profiles.active=local-full', "--server.port=$campaignPort",
            '--prizeflux.kafka.draw-queue-consumer-enabled=true',
            '--prizeflux.kafka.draw-queue-scan-initial-delay-ms=600000',
            '--prizeflux.kafka.draw-queue-scan-delay-ms=600000',
            '--prizeflux.traffic.draw-queue.enabled=true')
        $consumerStartedAt = [DateTimeOffset]::UtcNow
        $campaignConsumer = Start-ServiceProcess $campaignJarPath $logs.queueConsumerOut $logs.queueConsumerErr $consumerArgs
        $campaign = $campaignConsumer
        $null = Wait-HttpHealth "$campaignBaseUrl/api/health" $campaignConsumer @($logs.queueConsumerOut, $logs.queueConsumerErr)

        $drainDeadline = [DateTime]::UtcNow.AddSeconds(180)
        $snapshot = @()
        $workerSnapshot = @()
        $campaignSnapshotSql = @"
SELECT
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND status = 'COMPLETED'),
 (SELECT COUNT(*) FROM draw_queue_request WHERE campaign_id IN ($campaignIdCsv) AND status IN ('QUEUED','PROCESSING','RETRY_WAIT')),
 (SELECT COUNT(*) FROM draw_result WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'DrawQueued' AND aggregate_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'PrizeFulfillmentRequested' AND aggregate_id IN (SELECT id FROM draw_result WHERE campaign_id IN ($campaignIdCsv)))
"@ -replace '[\r\n]+', ' '
        $workerSnapshotSql = @"
SELECT
 (SELECT COUNT(*) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv) AND status = 'SUCCEEDED'),
 (SELECT COALESCE(SUM(attempt_count), 0) FROM fulfillment WHERE campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM fulfillment_compensation_task task JOIN fulfillment f ON f.id = task.fulfillment_id WHERE f.campaign_id IN ($campaignIdCsv)),
 (SELECT COUNT(*) FROM (SELECT draw_id FROM fulfillment WHERE campaign_id IN ($campaignIdCsv) GROUP BY draw_id HAVING COUNT(*) > 1) duplicate_rows)
"@ -replace '[\r\n]+', ' '
        do {
            $campaign.Refresh()
            if ($campaign.HasExited) { throw 'The draw-queue load consumer exited before the backlog drained.' }
            $snapshot = (Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' $campaignSnapshotSql) -split "`t"
            $workerSnapshot = (Invoke-DbScalar $workerDb 'prizeflux_worker' $workerPassword 'prizeflux_worker' $workerSnapshotSql) -split "`t"
            if ($snapshot.Count -eq 7 -and $workerSnapshot.Count -eq 5 -and $snapshot[0] -eq [string]$acceptedCount `
                    -and $snapshot[1] -eq [string]$acceptedCount -and $snapshot[2] -eq '0' `
                    -and $snapshot[3] -eq [string]$acceptedCount -and $snapshot[4] -eq '0' `
                    -and $snapshot[5] -eq [string]$acceptedCount -and $snapshot[6] -eq [string]$PrizeStock `
                    -and $workerSnapshot[0] -eq [string]$PrizeStock -and $workerSnapshot[1] -eq [string]$PrizeStock `
                    -and $workerSnapshot[2] -eq [string]$PrizeStock -and $workerSnapshot[3] -eq '0' -and $workerSnapshot[4] -eq '0') { break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $drainDeadline)
        if ($snapshot.Count -ne 7 -or $workerSnapshot.Count -ne 5 -or $snapshot[0] -ne [string]$acceptedCount `
                -or $snapshot[1] -ne [string]$acceptedCount -or $snapshot[2] -ne '0' `
                -or $snapshot[3] -ne [string]$acceptedCount -or $snapshot[4] -ne '0' `
                -or $snapshot[5] -ne [string]$acceptedCount -or $snapshot[6] -ne [string]$PrizeStock `
                -or $workerSnapshot[0] -ne [string]$PrizeStock -or $workerSnapshot[1] -ne [string]$PrizeStock `
                -or $workerSnapshot[2] -ne [string]$PrizeStock -or $workerSnapshot[3] -ne '0' -or $workerSnapshot[4] -ne '0') {
            throw "Queue load correctness failed (campaign=$($snapshot -join '/'); worker=$($workerSnapshot -join '/'); expected requests=$RequestCount, prizeStock=$PrizeStock)."
        }
        $queueDrainMs = [Math]::Round(([DateTimeOffset]::UtcNow - $consumerStartedAt).TotalMilliseconds, 2)
        $queueEndToEndMs = [Math]::Round(([DateTimeOffset]::UtcNow - $queueIngressStartedAt).TotalMilliseconds, 2)
        $requestRate = if ($queueBatchTimer.Elapsed.TotalMilliseconds -gt 0) {
            [Math]::Round(1000 * $acceptedCount / $queueBatchTimer.Elapsed.TotalMilliseconds, 2)
        } else { 0 }
        $report = [pscustomobject]@{
            Result = 'PASS'; Scenario = "Concurrent async draw-queue admission by $QueueBenchmarkUserCount synthetic users across $QueueBenchmarkCampaignCount campaigns with Kafka backlog drain and fulfillment reconciliation"
            Environment = [pscustomobject]@{
                OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
                JavaHome = $JavaHome; JavaVersion = (& $java -version 2>&1 | Out-String).Trim()
                ProcessorCount = [Environment]::ProcessorCount
                DockerEngine = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
                MySqlImage = 'mysql:8.4'; RedisImage = 'redis:7.4-alpine'
                Broker = 'Disposable single-node Kafka 3.9.1'; FulfillmentMode = 'SIMULATED'
            }
            PortAssignments = [pscustomobject]@{
                CampaignApi = $campaignPort; CampaignMySql = $campaignDbPort
                WorkerMySql = $workerDbPort; Kafka = $kafkaPort; Redis = $redisPort
            }
            Configuration = [pscustomobject]@{
                RequestCount = $RequestCount; Concurrency = $Concurrency; PrizeStock = $PrizeStock
                UserCount = $QueueBenchmarkUserCount; MaxRequestsAssignedPerUser = $maxRequestsPerUser
                CampaignCount = $QueueBenchmarkCampaignCount; MaxRequestsAssignedPerCampaign = $maxRequestsPerCampaign
                UserDailyLimit = $RequestCount; RedisQueueAdmissionEnabled = $true
                RedisMaxRequestsPerUser = $redisUserRequestLimit
                RedisMaxRequestsPerCampaign = $redisCampaignRequestLimit; RedisMaxRequestsGlobal = $RequestCount + 5
                QueueBacklogLimit = $effectiveQueueBacklogLimit
                DbPoolHighWatermark = 1.0; ConsumerInstances = 1; ConsumerConcurrency = 1
                TopicPartitions = 12; PeriodicRecoveryScanDelayed = $true
                ConsumerStartedAfterQueueOutboxPublished = $true; PersistentVolumes = $false
            }
            Metrics = [pscustomobject]@{
                RequestsAttempted = $RequestCount
                QueueAdmissions = $acceptedCount
                ServiceUnavailableResponses = $serviceUnavailableCount
                ProtectionRejectionReasonCounts = $protectionReasonCounts
                QueueAdmissionElapsedMs = [Math]::Round($queueBatchTimer.Elapsed.TotalMilliseconds, 2)
                QueueAdmissionsPerSecond = $requestRate
                AdmissionP50Ms = Get-Percentile $latencies 0.50
                AdmissionP95Ms = Get-Percentile $latencies 0.95
                AdmissionP99Ms = Get-Percentile $latencies 0.99
                QueueOutboxPublishWaitMs = [Math]::Round(($outboxPublishedAt - $queueIngressStartedAt).TotalMilliseconds - $queueBatchTimer.Elapsed.TotalMilliseconds, 2)
                BacklogAtConsumerStart = $backlogAtConsumerStart
                ConsumerDrainAndWorkerCompletionMs = $queueDrainMs
                EndToEndFromIngressStartMs = $queueEndToEndMs
                UnexpectedHttpResponses = $unexpectedResponses.Count
            }
            Correctness = [pscustomobject]@{
                QueueRows = [int]$snapshot[0]; CompletedQueueRows = [int]$snapshot[1]
                PendingQueueRows = [int]$snapshot[2]; DrawRows = [int]$snapshot[3]
                InventoryRemaining = [int]$snapshot[4]; SentDrawQueuedOutboxRows = [int]$snapshot[5]
                SentPrizeFulfillmentOutboxRows = [int]$snapshot[6]; WorkerLedgerRows = [int]$workerSnapshot[0]
                WorkerSucceededRows = [int]$workerSnapshot[1]; WorkerAttemptCountTotal = [int]$workerSnapshot[2]
                ManualReviewTasks = [int]$workerSnapshot[3]; DuplicateDrawFulfillments = [int]$workerSnapshot[4]
            }
            PerUserOutcomes = $perUserOutcomes; PerCampaignOutcomes = $perCampaignOutcomes
            Samples = @($samples | Select-Object Index, UserSlot, CampaignSlot, HttpStatus, ErrorCode, QueueStatus, QueueRequestId, LatencyMs, ErrorType)
            CredentialsOrTokensPrinted = $false
            Note = "Single host, $QueueBenchmarkUserCount synthetic users and $QueueBenchmarkCampaignCount campaigns assigned round-robin, one Campaign queue consumer, single-node Kafka, Redis/MySQL temporary containers, simulated fulfillment; each HTTP 503/PFX-9001 must reconcile to a low-cardinality server log protection reason, all other non-202 responses fail; controlled local result, not production capacity evidence."
        }
        Write-BenchmarkEvidence $evidenceFullPath $report
        [pscustomobject]@{
            Result = 'PASS'; Scenario = $report.Scenario; RequestsAttempted = $RequestCount
            UserCount = $QueueBenchmarkUserCount; CampaignCount = $QueueBenchmarkCampaignCount
            PerUserOutcomes = $perUserOutcomes; PerCampaignOutcomes = $perCampaignOutcomes
            QueueAdmissions = $acceptedCount; ServiceUnavailableResponses = $report.Metrics.ServiceUnavailableResponses
            ProtectionRejectionReasonCounts = $protectionReasonCounts
            QueueAdmissionsPerSecond = $requestRate; AdmissionP95Ms = Get-Percentile $latencies 0.95
            BacklogAtConsumerStart = $backlogAtConsumerStart; ConsumerDrainAndWorkerCompletionMs = $queueDrainMs
            DrawRows = $snapshot[3]; InventoryRemaining = $snapshot[4]
            SentDrawQueuedOutboxRows = $snapshot[5]; WorkerSucceededRows = $workerSnapshot[1]
            EvidencePath = if ($evidenceFullPath) { [IO.Path]::GetRelativePath($repoRoot, $evidenceFullPath) } else { '' }
            CredentialsOrTokensPrinted = $false
        } | Format-List
        return
    }

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
    if ($InjectKafkaOutage -or $InjectDrawQueueKafkaOutage) {
        $kafkaOutageStartedAt = [DateTimeOffset]::UtcNow
        & docker stop --time 2 $kafka | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the disposable Kafka broker for outage injection.' }
    }

    if ($InjectDrawQueueKafkaOutage) {
        $requestKey = "queue-$tag"
        $queueRequest = @{ campaignId = $campaignId; requestKey = $requestKey } | ConvertTo-Json -Compress
        $accepted = Invoke-RestMethod -Method Post -Uri "$campaignBaseUrl/api/draw-queue" `
            -Headers $userHeaders -ContentType 'application/json' -Body $queueRequest
        $queueId = [long]$accepted.data.queueRequestId
        if ($queueId -le 0 -or $accepted.data.status -ne 'QUEUED') {
            throw 'The queue API did not durably accept the outage-test request as QUEUED.'
        }

        $outboxRetryDeadline = [DateTime]::UtcNow.AddSeconds(35)
        $outboxRetryState = ''
        while ([DateTime]::UtcNow -lt $outboxRetryDeadline) {
            $outboxRetryState = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_type = 'DrawQueued' AND aggregate_id = $campaignId"
            if ($outboxRetryState -match '^PENDING:([1-9][0-9]*)$') { break }
            if ($outboxRetryState.StartsWith('DEAD:')) { throw 'The queue notification Outbox exhausted retries while Kafka was intentionally unavailable.' }
            Start-Sleep -Milliseconds 300
        }
        $attemptMatch = [regex]::Match($outboxRetryState, '^PENDING:([1-9][0-9]*)$')
        if (-not $attemptMatch.Success) {
            throw 'Kafka outage did not leave the DrawQueued Outbox event in a retryable PENDING state.'
        }
        $attemptsBeforeRecovery = [int]$attemptMatch.Groups[1].Value
        $queueBeforeRecovery = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT CONCAT(status, ':', attempts) FROM draw_queue_request WHERE id = $queueId"
        if ($queueBeforeRecovery -ne 'QUEUED:0') {
            throw 'The durable draw-queue row changed while Kafka was unavailable; expected QUEUED with no processing attempts.'
        }

        & docker @kafkaArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart the disposable Kafka broker after draw-queue outage injection.' }
        Wait-TcpService '127.0.0.1' $kafkaPort 120
        $brokerReadyAt = [DateTimeOffset]::UtcNow

        $publishedDeadline = [DateTime]::UtcNow.AddSeconds(45)
        $outboxBeforeConsumer = ''
        while ([DateTime]::UtcNow -lt $publishedDeadline) {
            $outboxBeforeConsumer = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_type = 'DrawQueued' AND aggregate_id = $campaignId"
            if ($outboxBeforeConsumer -match '^SENT:[1-9][0-9]*$') { break }
            Start-Sleep -Milliseconds 300
        }
        if ($outboxBeforeConsumer -notmatch '^SENT:[1-9][0-9]*$') {
            throw "The DrawQueued event did not reach Kafka before consumer startup (outbox=$outboxBeforeConsumer)."
        }
        $queueBeforeConsumer = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
            "SELECT CONCAT(status, ':', attempts) FROM draw_queue_request WHERE id = $queueId"
        if ($queueBeforeConsumer -ne 'QUEUED:0') {
            throw "Queue processing started before the new consumer group (queue=$queueBeforeConsumer)."
        }

        Stop-Process -Id $campaign.Id -Force
        $campaign.WaitForExit()
        $processIds.Remove($campaign.Id) | Out-Null
        $env:PFX_DRAW_QUEUE_CONSUMER_ENABLED = 'true'
        $consumerArgs = @('--spring.profiles.active=local-full', "--server.port=$campaignPort",
            '--prizeflux.kafka.draw-queue-consumer-enabled=true',
            '--prizeflux.kafka.draw-queue-scan-initial-delay-ms=600000',
            '--prizeflux.kafka.draw-queue-scan-delay-ms=600000',
            '--prizeflux.traffic.draw-queue.enabled=false')
        if ($relayConfigurationOverrideAllowed) {
            $consumerArgs += "--prizeflux.outbox.relay.fixed-delay=${effectiveOutboxRelayDelayMilliseconds}"
            $consumerArgs += "--prizeflux.outbox.relay.parallelism=$OutboxRelayParallelism"
        }
        if ($hikariConfigurationOverrideAllowed) {
            $consumerArgs += "--spring.datasource.hikari.maximum-pool-size=$QueueBenchmarkHikariMaximumPoolSize"
        }
        $consumerStartedAt = [DateTimeOffset]::UtcNow
        $campaign = Start-ServiceProcess $campaignJarPath $logs.campaignConsumerOut $logs.campaignConsumerErr $consumerArgs
        $null = Wait-HttpHealth "$campaignBaseUrl/api/health" $campaign @($logs.campaignConsumerOut, $logs.campaignConsumerErr)

        $queueDeadline = [DateTime]::UtcNow.AddSeconds(90)
        $queueState = ''
        $drawRows = '0'
        $remainingStock = '-1'
        $sentQueueOutboxRows = '0'
        while ([DateTime]::UtcNow -lt $queueDeadline) {
            $queueState = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT status FROM draw_queue_request WHERE id = $queueId"
            $drawRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $campaignId"
            $remainingStock = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COALESCE(SUM(remaining_stock), 0) FROM prize_inventory WHERE campaign_id = $campaignId"
            $sentQueueOutboxRows = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT' AND event_type = 'DrawQueued' AND aggregate_id = $campaignId"
            if ($queueState -in @('COMPLETED', 'REJECTED', 'MANUAL_REVIEW') -and $sentQueueOutboxRows -eq '1') { break }
            Start-Sleep -Milliseconds 500
        }
        if ($queueState -ne 'COMPLETED' -or $drawRows -ne '1' -or $remainingStock -ne '0' `
                -or $sentQueueOutboxRows -ne '1') {
            $queueDiagnostic = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT CONCAT(status, ':', attempts, ':', COALESCE(error_code, '-'), ':', COALESCE(last_error_code, '-')) FROM draw_queue_request WHERE id = $queueId"
            $outboxDiagnostic = Invoke-DbScalar $campaignDb 'prizeflux' $campaignPassword 'prizeflux' `
                "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_type = 'DrawQueued' AND aggregate_id = $campaignId"
            throw "Draw-queue recovery invariant failed (queue=$queueDiagnostic; outbox=$outboxDiagnostic; draws=$drawRows; inventory=$remainingStock; sentDrawQueuedOutboxRows=$sentQueueOutboxRows)."
        }
        $faultInjection = [pscustomobject]@{
            Dependency = 'Kafka draw-queue topic'; QueueOutboxStateBeforeRecovery = 'PENDING'
            PublishAttemptsBeforeRecovery = $attemptsBeforeRecovery; QueueStateBeforeRecovery = $queueBeforeRecovery
            OutboxStateBeforeConsumerStart = $outboxBeforeConsumer; QueueStateBeforeConsumerStart = $queueBeforeConsumer
            OffsetResetPolicy = 'earliest'; Recovery = 'Broker restarted; DrawQueued was confirmed in Kafka before a fresh consumer group started; queue completed without periodic scan fallback'
            TimeToBrokerPortMs = [Math]::Round(($brokerReadyAt - $kafkaOutageStartedAt).TotalMilliseconds, 2)
            QueueCompletionMsFromConsumerStart = [Math]::Round(([DateTimeOffset]::UtcNow - $consumerStartedAt).TotalMilliseconds, 2)
        }
        $report = [pscustomobject]@{
            Result = 'PASS'; Scenario = 'Draw-queue Kafka outage with durable Outbox notification and consumer recovery'
            Environment = [pscustomobject]@{
                OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
                JavaHome = $JavaHome; JavaVersion = (& $java -version 2>&1 | Out-String).Trim()
                DockerEngine = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
                MySqlImage = 'mysql:8.4'; Broker = 'Disposable single-node Kafka 3.9.1'
            }
            PortAssignments = [pscustomobject]@{
                CampaignApi = $campaignPort; CampaignMySql = $campaignDbPort
                WorkerMySql = $workerDbPort; Kafka = $kafkaPort
            }
            Configuration = [pscustomobject]@{
                QueueRequests = 1; ConsumerEnabled = $true; PeriodicRecoveryScanDelayed = $true
                ConsumerStartedAfterKafkaPublish = $true; QueueConsumerConcurrencyPerInstance = $QueueConsumerConcurrency
                CampaignHikariMaximumPoolSize = $QueueBenchmarkHikariMaximumPoolSize
                OutboxRelayParallelism = $OutboxRelayParallelism
                OutboxRelayClaimLimit = $(if ($OutboxRelayParallelism -eq 1) {50} else {$OutboxRelayParallelism})
                OutboxRelayFixedDelayMilliseconds = $effectiveOutboxRelayDelayMilliseconds
                QueueKey = 'redacted'; PersistentVolumes = $false
            }
            Correctness = [pscustomobject]@{
                QueueStateBeforeRecovery = $queueBeforeRecovery; QueueStateAfterRecovery = $queueState
                DrawRows = [int]$drawRows; InventoryRemaining = [int]$remainingStock
                SentDrawQueuedOutboxRows = [int]$sentQueueOutboxRows
            }
            FaultInjection = $faultInjection; CredentialsOrTokensPrinted = $false
            Note = 'One request, one consumer process and one disposable broker. Proves durable enqueue/notification and recovery; not consumer rebalance, multi-broker HA or capacity.'
        }
        Write-BenchmarkEvidence $evidenceFullPath $report
        [pscustomobject]@{
            Result = 'PASS'; Scenario = $report.Scenario; QueueStateBeforeRecovery = $queueBeforeRecovery
            QueueStateAfterRecovery = $queueState; DrawRows = $drawRows
            InventoryRemaining = $remainingStock; SentDrawQueuedOutboxRows = $sentQueueOutboxRows
            KafkaRecoveryMs = $faultInjection.TimeToBrokerPortMs
            EvidencePath = if ($evidenceFullPath) { [IO.Path]::GetRelativePath($repoRoot, $evidenceFullPath) } else { '' }
            CredentialsOrTokensPrinted = $false
        } | Format-List
        return
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
        Wait-TcpService '127.0.0.1' $kafkaPort 120
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
        Scenario = if ($InjectKafkaOutage) {
            'Kafka broker outage with Outbox retry and Worker recovery'
        } elseif ($InjectCampaignMySqlOutage) {
            'Campaign MySQL outage with fail-closed draw and state preservation'
        } elseif ($InjectRedisOutage) {
            'Redis limiter outage with fail-closed draw and state preservation'
        } elseif ($InjectTransientFulfillmentFailure) {
            'Authenticated draw plus one injected transient simulated fulfillment failure and durable retry recovery'
        } else { 'Authenticated concurrent draw HTTP benchmark with isolated Campaign/Worker MySQL and Kafka' }
        Environment = [pscustomobject]@{
            OS = [Environment]::OSVersion.VersionString; PowerShell = $PSVersionTable.PSVersion.ToString()
            JavaHome = $JavaHome; JavaVersion = $javaVersion; ProcessorCount = [Environment]::ProcessorCount
            DockerEngine = $dockerVersion; MySqlImage = 'mysql:8.4'
            RedisImage = if ($EnableRedisLimiter) { 'redis:7.4-alpine' } else { 'disabled' }
            Broker = 'Disposable single-node Kafka 3.9.1'; FulfillmentMode = 'SIMULATED'
        }
        PortAssignments = [pscustomobject]@{
            CampaignApi = $campaignPort; CampaignMySql = $campaignDbPort
            WorkerMySql = $workerDbPort; Kafka = $kafkaPort; Redis = $redisPort
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
} catch {
    if ($InjectDrawQueueKafkaOutage -or $InjectDrawQueueKafkaRebalance -or $BenchmarkDrawQueue) {
        $diagnosticLines = foreach ($path in @($logs.campaignOut, $logs.campaignErr,
                $logs.campaignConsumerOut, $logs.campaignConsumerErr,
                $logs.rebalanceCampaignOut, $logs.rebalanceCampaignErr,
                $logs.queueConsumerOut, $logs.queueConsumerErr)) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                Get-Content -LiteralPath $path | Where-Object {
                    $_ -match 'KafkaMessageListenerContainer|draw-queue|ConsumerCoordinator|Connection to node|Failed to|ERROR|Exception' `
                        -and $_ -notmatch 'KafkaConfig values|ConsumerConfig values|ProducerConfig values'
                }
            }
        }
        $safeDiagnostics = @($diagnosticLines | Select-Object -Last 18 | ForEach-Object {
            $_ -replace '(?i)(password|secret|token|authorization)(\s*[:=]\s*)[^\s,;]+', '$1=[redacted]'
        })
        if ($safeDiagnostics.Count -gt 0) { Write-Output 'Redacted queue Kafka diagnostics:'; $safeDiagnostics }
    }
    throw
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
