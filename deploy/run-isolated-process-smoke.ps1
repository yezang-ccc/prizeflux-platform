[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $PSScriptRoot "compose.smoke.yml"
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$projectName = "pfx-smoke-$runId"
$logDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "prizeflux-smoke-$runId"
$null = New-Item -ItemType Directory -Path $logDirectory
$managedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$criticalProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$savedEnvironment = @{}
$environmentTouched = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$composeStarted = $false
$restoreVolumeName = $null
$restoreContainerName = $null
$restoreVolumeCreated = $false
$restoreContainerCreated = $false
$mysqlRootPassword = $null

function Set-SmokeEnvironment([string] $Name, [string] $Value) {
    if ($environmentTouched.Add($Name)) {
        $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Get-RandomHex([int] $ByteCount = 32) {
    $bytes = [byte[]]::new($ByteCount)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return [int]$listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Invoke-Compose([string[]] $Arguments) {
    & docker compose --project-name $projectName --file $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Disposable smoke infrastructure command failed." }
}

function Invoke-MySqlAdminSql([string] $Sql) {
    $Sql | & docker compose --project-name $projectName --file $composeFile exec --no-TTY mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
    if ($LASTEXITCODE -ne 0) { throw "Disposable MySQL schema/account bootstrap failed." }
}

function Invoke-MySqlRuntimeScalar([string] $Password, [string] $Sql) {
    $output = $Sql | & docker compose --project-name $projectName --file $composeFile exec --no-TTY `
        --env "MYSQL_PWD=$Password" mysql sh -lc 'mysql -h127.0.0.1 -upfx_campaign_runtime --database=prizeflux -N'
    if ($LASTEXITCODE -ne 0) { throw "Disposable MySQL runtime query failed." }
    return (($output | Out-String).Trim())
}

function Invoke-ContainerMySqlScalar(
    [string] $ContainerName,
    [string] $DatabaseUser,
    [string] $Password,
    [string] $Database,
    [string] $Sql
) {
    $output = $Sql | & docker exec --interactive --env "MYSQL_PWD=$Password" $ContainerName `
        mysql --batch --skip-column-names "--user=$DatabaseUser" "--database=$Database"
    if ($LASTEXITCODE -ne 0) { throw "Disposable MySQL query failed for schema $Database." }
    return (($output | Out-String).Trim())
}

function Get-DockerLabelValue($Labels, [string] $Name) {
    if ($null -eq $Labels) { return $null }
    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return [string]$property.Value
}

function Assert-RuntimePrivilegeDenied(
    [string] $DatabaseUser,
    [string] $Password,
    [string] $Database,
    [string] $Sql,
    [string] $Label
) {
    $output = $Sql | & docker compose --project-name $projectName --file $composeFile exec --no-TTY --env "MYSQL_PWD=$Password" mysql sh -lc "mysql -h127.0.0.1 -u$DatabaseUser --database=$Database -N" 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { throw "${Label}: runtime account unexpectedly had the requested privilege." }
    if (($output | Out-String) -notmatch "denied to user") {
        throw "${Label}: command failed for a reason other than the expected MySQL privilege denial."
    }
    Write-Output "PASS least-privilege check: $Label"
}

function Start-SmokeProcess(
    [string] $FilePath,
    [string[]] $Arguments,
    [string] $WorkingDirectory,
    [string] $LogName
) {
    $stdout = Join-Path $logDirectory "$LogName.stdout.log"
    $stderr = Join-Path $logDirectory "$LogName.stderr.log"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $managedProcesses.Add($process)
    return $process
}

function Wait-HttpReady(
    [string] $Name,
    [string] $Url,
    [int] $TimeoutSeconds = 120,
    [System.Diagnostics.Process] $Process = $null
) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $Process) {
            $Process.Refresh()
            if ($Process.HasExited) { throw "$Name process exited before becoming ready." }
        }
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 2
            return $response
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "$Name did not become ready within $TimeoutSeconds seconds. Logs: $logDirectory"
}

function Wait-LoopbackListenerClosed([int] $Port, [string] $Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
        if (-not $listener) { return }
        Start-Sleep -Milliseconds 250
    }
    throw "$Name still has a listening TCP socket after shutdown."
}

try {
    $docker = Get-Command docker -ErrorAction Stop
    $java = Get-Command java -ErrorAction Stop
    $uv = Get-Command uv -ErrorAction Stop
    $npm = Get-Command npm -ErrorAction Stop
    $node = Get-Command node -ErrorAction Stop
    $null = & $docker.Source info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Docker Engine is not reachable." }

    $javaHome = Split-Path (Split-Path $java.Source -Parent) -Parent
    Set-SmokeEnvironment "JAVA_HOME" $javaHome
    $mysqlRootPassword = Get-RandomHex
    Set-SmokeEnvironment "PFX_SMOKE_MYSQL_ROOT_PASSWORD" $mysqlRootPassword
    $mysqlPort = Get-FreeLoopbackPort
    $redisPort = Get-FreeLoopbackPort
    $kafkaPort = Get-FreeLoopbackPort
    Set-SmokeEnvironment "PFX_SMOKE_MYSQL_PORT" ([string]$mysqlPort)
    Set-SmokeEnvironment "PFX_SMOKE_REDIS_PORT" ([string]$redisPort)
    Set-SmokeEnvironment "PFX_SMOKE_KAFKA_PORT" ([string]$kafkaPort)
    Set-SmokeEnvironment "KAFKA_BOOTSTRAP_SERVERS" "127.0.0.1:$kafkaPort"

    $accounts = @{
        CampaignRuntime = Get-RandomHex
        CampaignMigrator = Get-RandomHex
        WorkerRuntime = Get-RandomHex
        WorkerMigrator = Get-RandomHex
        AgentRuntime = Get-RandomHex
        AgentMigrator = Get-RandomHex
    }

    Write-Output "Starting disposable MySQL/Redis/Kafka on loopback only (Compose project $projectName)."
    Invoke-Compose @("up", "--detach", "--wait")
    $composeStarted = $true

    $bootstrapSql = @"
CREATE DATABASE IF NOT EXISTS prizeflux CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE prizeflux_worker CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE prizeflux_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pfx_campaign_runtime'@'%' IDENTIFIED BY '$($accounts.CampaignRuntime)';
CREATE USER 'pfx_campaign_migrator'@'%' IDENTIFIED BY '$($accounts.CampaignMigrator)';
CREATE USER 'pfx_worker_runtime'@'%' IDENTIFIED BY '$($accounts.WorkerRuntime)';
CREATE USER 'pfx_worker_migrator'@'%' IDENTIFIED BY '$($accounts.WorkerMigrator)';
CREATE USER 'pfx_agent_runtime'@'%' IDENTIFIED BY '$($accounts.AgentRuntime)';
CREATE USER 'pfx_agent_migrator'@'%' IDENTIFIED BY '$($accounts.AgentMigrator)';
GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux.* TO 'pfx_campaign_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux.* TO 'pfx_campaign_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux_worker.* TO 'pfx_worker_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux_worker.* TO 'pfx_worker_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux_agent.* TO 'pfx_agent_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux_agent.* TO 'pfx_agent_migrator'@'%';
FLUSH PRIVILEGES;
"@
    Invoke-MySqlAdminSql $bootstrapSql
    Write-Output "Created three temporary schemas with isolated migration and runtime accounts."

    $mavenWrapper = Join-Path $repoRoot "backend\mvnw.cmd"
    Push-Location (Join-Path $repoRoot "backend")
    try {
        & $mavenWrapper -pl prizeflux-campaign,prizeflux-worker,prizeflux-ai -am -DskipTests package | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Java application packaging failed." }
    } finally { Pop-Location }
    Push-Location (Join-Path $repoRoot "web")
    try {
        & $npm.Source run build | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Vue production build failed." }
    } finally { Pop-Location }

    $dbUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/prizeflux"
    $workerDbUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/prizeflux_worker"
    $agentUrl = "mysql+asyncmy://pfx_agent_runtime:$($accounts.AgentRuntime)@127.0.0.1:$mysqlPort/prizeflux_agent"
    $agentMigrationUrl = "mysql+asyncmy://pfx_agent_migrator:$($accounts.AgentMigrator)@127.0.0.1:$mysqlPort/prizeflux_agent"

    Set-SmokeEnvironment "PFX_AGENT_MIGRATION_DATABASE_URL" $agentMigrationUrl
    Push-Location (Join-Path $repoRoot "agent-service")
    try {
        & $uv.Source run --locked alembic upgrade head | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Agent Alembic migration failed." }
    } finally { Pop-Location }
    Write-Output "PASS Agent Alembic migration using the dedicated schema DDL account."

    Set-SmokeEnvironment "PFX_DB_URL" $dbUrl
    Set-SmokeEnvironment "PFX_DB_USER" "pfx_campaign_runtime"
    Set-SmokeEnvironment "PFX_DB_PASSWORD" $accounts.CampaignRuntime
    Set-SmokeEnvironment "PFX_CAMPAIGN_MIGRATION_DB_URL" $dbUrl
    Set-SmokeEnvironment "PFX_CAMPAIGN_MIGRATION_DB_USER" "pfx_campaign_migrator"
    Set-SmokeEnvironment "PFX_CAMPAIGN_MIGRATION_DB_PASSWORD" $accounts.CampaignMigrator
    Set-SmokeEnvironment "PFX_REDIS_PORT" ([string]$redisPort)
    Set-SmokeEnvironment "PFX_JWT_SECRET" (Get-RandomHex 48)
    $suffix = $runId.Substring(0, 8)
    $operatorName = "p14-operator-$suffix"
    $operatorPassword = Get-RandomHex 24
    Set-SmokeEnvironment "PFX_BOOTSTRAP_OPERATOR_USERNAME" $operatorName
    Set-SmokeEnvironment "PFX_BOOTSTRAP_OPERATOR_PASSWORD" $operatorPassword
    Set-SmokeEnvironment "PFX_DRAW_QUEUE_CONSUMER_ENABLED" "true"
    # Keep periodic DB recovery out of this smoke so the queued draw proves its Kafka notification path.
    Set-SmokeEnvironment "PFX_DRAW_QUEUE_SCAN_INITIAL_DELAY_MS" "600000"
    Set-SmokeEnvironment "PFX_DRAW_QUEUE_SCAN_DELAY_MS" "600000"
    $campaignJar = Join-Path $repoRoot "backend\prizeflux-campaign\target\prizeflux-campaign-0.1.0-SNAPSHOT.jar"
    $javaArgs = @(
        "-jar", $campaignJar, "--spring.profiles.active=local-full", "--server.port=8081",
        "--prizeflux.security.jwt.ttl=PT20S", "--prizeflux.bootstrap.operator.enabled=true"
    )
    $campaignProcess = Start-SmokeProcess $java.Source $javaArgs $repoRoot "campaign"
    $criticalProcesses.Add($campaignProcess)
    $null = Wait-HttpReady "Campaign" "http://127.0.0.1:8081/api/health" -Process $campaignProcess
    Set-SmokeEnvironment "PFX_BOOTSTRAP_OPERATOR_USERNAME" $null
    Set-SmokeEnvironment "PFX_BOOTSTRAP_OPERATOR_PASSWORD" $null
    Write-Output "PASS Campaign ready; Flyway migrated prizeflux using its DDL account."

    Set-SmokeEnvironment "PFX_WORKER_DB_URL" $workerDbUrl
    Set-SmokeEnvironment "PFX_WORKER_DB_USER" "pfx_worker_runtime"
    Set-SmokeEnvironment "PFX_WORKER_DB_PASSWORD" $accounts.WorkerRuntime
    Set-SmokeEnvironment "PFX_WORKER_MIGRATION_DB_URL" $workerDbUrl
    Set-SmokeEnvironment "PFX_WORKER_MIGRATION_DB_USER" "pfx_worker_migrator"
    Set-SmokeEnvironment "PFX_WORKER_MIGRATION_DB_PASSWORD" $accounts.WorkerMigrator
    $workerJar = Join-Path $repoRoot "backend\prizeflux-worker\target\prizeflux-worker-0.1.0-SNAPSHOT.jar"
    $workerProcess = Start-SmokeProcess $java.Source @(
        "-jar", $workerJar, "--prizeflux.worker.fulfillment.mode=simulated",
        "--prizeflux.worker.retry.enabled=true",
        "--prizeflux.worker.retry.scheduler-enabled=true",
        "--prizeflux.worker.retry.poll-ms=500"
    ) $repoRoot "worker"
    $workerDeadline = [DateTime]::UtcNow.AddSeconds(60)
    $workerLog = Join-Path $logDirectory "worker.stdout.log"
    while ([DateTime]::UtcNow -lt $workerDeadline) {
        $workerProcess.Refresh()
        if ($workerProcess.HasExited) { throw "Worker process exited during startup." }
        if ((Test-Path $workerLog) -and (Select-String -Path $workerLog -Pattern "Started PrizeFluxWorkerApplication" -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path $workerLog) -or -not (Select-String -Path $workerLog -Pattern "Started PrizeFluxWorkerApplication" -Quiet)) {
        throw "Worker did not finish startup within 60 seconds. Logs: $logDirectory"
    }
    $criticalProcesses.Add($workerProcess)
    Write-Output "PASS Worker started, migrated prizeflux_worker, and connected to the disposable Kafka broker."

    $aiJar = Join-Path $repoRoot "backend\prizeflux-ai\target\prizeflux-ai-0.1.0-SNAPSHOT.jar"
    Set-SmokeEnvironment "PFX_AI_PORT" "8082"
    $aiProcess = Start-SmokeProcess $java.Source @("-jar", $aiJar) $repoRoot "legacy-ai"
    $criticalProcesses.Add($aiProcess)
    $null = Wait-HttpReady "Legacy Java AI" "http://127.0.0.1:8082/api/health" -Process $aiProcess

    Set-SmokeEnvironment "PFX_AGENT_DATABASE_URL" $agentUrl
    Set-SmokeEnvironment "PFX_AGENT_HOST" "127.0.0.1"
    Set-SmokeEnvironment "PFX_AGENT_PORT" "8083"
    Set-SmokeEnvironment "PFX_AGENT_CAMPAIGN_BASE_URL" "http://127.0.0.1:8081"
    Set-SmokeEnvironment "PFX_AGENT_MODEL_PROVIDER" "fake"
    Set-SmokeEnvironment "PFX_AGENT_KNOWLEDGE_RETRIEVAL_ENABLED" "false"
    $agentPython = Join-Path $repoRoot "agent-service\.venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $agentPython -PathType Leaf)) { throw "uv did not create the locked Agent virtual environment." }
    $agentProcess = Start-SmokeProcess $agentPython @("-m", "prizeflux_agent") (Join-Path $repoRoot "agent-service") "agent"
    $criticalProcesses.Add($agentProcess)
    $ready = Wait-HttpReady "Python Agent" "http://127.0.0.1:8083/health/ready" -Process $agentProcess
    if ($ready.status -ne "READY") { throw "Agent readiness returned a non-ready status." }

    $viteEntry = Join-Path $repoRoot "web\node_modules\vite\bin\vite.js"
    $webProcess = Start-SmokeProcess $node.Source @(
        $viteEntry, "--host", "127.0.0.1", "--port", "5173", "--strictPort"
    ) (Join-Path $repoRoot "web") "web"
    $criticalProcesses.Add($webProcess)
    $webResponse = Wait-HttpReady "Vue Web" "http://127.0.0.1:5173/" -Process $webProcess
    if (-not [string]$webResponse) { throw "Vue Web returned an empty response." }
    Write-Output "PASS Campaign, Worker, Python Agent, legacy Java AI, and Vue processes are running."

    $userName = "p14-smoke-$suffix"
    $userPassword = Get-RandomHex 24
    $registerBody = @{ username = $userName; password = $userPassword } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/register" `
        -ContentType "application/json" -Body $registerBody -TimeoutSec 10
    $loginBody = @{ username = $userName; password = $userPassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" -Body $loginBody -TimeoutSec 10
    $token = [string]$login.data.token
    if ([string]::IsNullOrWhiteSpace($token)) { throw "Campaign login did not return a token." }
    $headers = @{ Authorization = "Bearer $token" }
    $me = Invoke-RestMethod -Uri "http://127.0.0.1:8081/api/auth/me" -Headers $headers -TimeoutSec 10
    if ($me.data.username -ne $userName) { throw "Campaign identity round-trip did not match the test user." }
    Write-Output "PASS synthetic USER registration, login, and server-side identity verification."

    $draft = @{
        name = "P14 smoke draft"
        startsAt = [DateTimeOffset]::UtcNow.AddHours(2).ToString("o")
        endsAt = [DateTimeOffset]::UtcNow.AddHours(3).ToString("o")
        dailyLimit = 5
        prizes = @(@{ id = 1; name = "Smoke prize"; totalStock = 10; probability = 1.0 })
    } | ConvertTo-Json -Depth 5 -Compress
    $creatorCheck = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/api/ai/creator/validate" `
        -Headers $headers -ContentType "application/json" -Body $draft -TimeoutSec 10
    if (-not $creatorCheck.data.valid -or -not $creatorCheck.data.simulated `
            -or $creatorCheck.data.status -ne "COMPLETED" `
            -or -not ($creatorCheck.data.trace | Where-Object { $_.toolName -eq "campaign.draft.validate" })) {
        throw "Legacy Creator validation did not complete its read-only Fake tool workflow."
    }
    Write-Output "PASS legacy Creator route completed an authenticated, read-only Fake validation tool call."

    $session = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8083/api/ai/v2/sessions" `
        -Headers $headers -TimeoutSec 10
    $runBody = @{
        clientMessageId = "smoke-$runId"
        expectedVersion = 0
        input = "Say hello for this local process smoke."
    } | ConvertTo-Json -Compress
    $accepted = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:8083/api/ai/v2/sessions/$($session.sessionId)/runs" `
        -Headers $headers -ContentType "application/json" -Body $runBody -TimeoutSec 10
    $run = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        $run = Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/ai/v2/runs/$($accepted.runId)" `
            -Headers $headers -TimeoutSec 10
        if ($run.status -in @("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) { break }
    }
    if ($run.status -ne "COMPLETED") { throw "Fake Agent run did not complete successfully." }
    $history = Invoke-RestMethod `
        -Uri "http://127.0.0.1:8083/api/ai/v2/sessions/$($session.sessionId)" -Headers $headers -TimeoutSec 10
    if ($history.history.Count -ne 1) { throw "Agent run history was not persisted." }
    $sse = Invoke-WebRequest -Uri "http://127.0.0.1:8083/api/ai/v2/runs/$($accepted.runId)/events" `
        -Headers $headers -TimeoutSec 15
    if ($sse.Content -notmatch "event:") { throw "Agent SSE event stream contained no events." }
    Write-Output "PASS owner-scoped Agent session/run, Fake completion, MySQL history, and SSE replay."

    # Exercise the persistent Creator mode through Python Agent -> authenticated Java validation.
    # Keep this isolated smoke deterministic: Fake is configured above and no provider is contacted.
    $freshLogin = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" `
        -Body (@{ username = $userName; password = $userPassword } | ConvertTo-Json -Compress) `
        -TimeoutSec 10
    $headers = @{ Authorization = "Bearer $($freshLogin.data.token)" }
    $creatorSession = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8083/api/ai/v2/sessions" `
        -Headers $headers -TimeoutSec 10
    $creatorDraft = @{
        name = "Persistent Creator smoke draft"
        startsAt = [DateTimeOffset]::UtcNow.AddHours(2).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
        endsAt = [DateTimeOffset]::UtcNow.AddHours(3).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
        dailyLimit = 5
        prizes = @(
            @{ id = 1; name = "Creator smoke prize A"; totalStock = 10; probability = [decimal]0.7 },
            @{ id = 2; name = "Creator smoke prize B"; totalStock = 10; probability = [decimal]0.5 }
        )
    }
    $javaCreatorCheck = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:8081/api/me/campaigns/validate" `
        -Headers $headers -ContentType "application/json" `
        -Body ($creatorDraft | ConvertTo-Json -Depth 6 -Compress) -TimeoutSec 10
    $javaCreatorFindings = @($javaCreatorCheck.data.findings)
    if ($javaCreatorCheck.data.valid `
            -or $javaCreatorFindings.Count -ne 1 `
            -or $javaCreatorFindings[0].code -ne "CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED") {
        throw "Java Creator preflight did not return the expected deterministic probability finding."
    }
    Write-Output "PASS direct Java Creator preflight returned the expected deterministic finding."
    $creatorRunBody = @{
        clientMessageId = "creator-smoke-$runId"
        expectedVersion = 0
        input = "Review this draft and keep Java validation findings authoritative."
        mode = "CREATOR_DRAFT_REVIEW"
        context = @{ schemaVersion = 1; draft = $creatorDraft }
    } | ConvertTo-Json -Depth 8 -Compress
    $creatorAccepted = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:8083/api/ai/v2/sessions/$($creatorSession.sessionId)/runs" `
        -Headers $headers -ContentType "application/json" -Body $creatorRunBody -TimeoutSec 10
    $creatorRun = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        $creatorRun = Invoke-RestMethod `
            -Uri "http://127.0.0.1:8083/api/ai/v2/runs/$($creatorAccepted.runId)" `
            -Headers $headers -TimeoutSec 10
        if ($creatorRun.status -in @("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) { break }
    }
    $authoritativeFindings = @($creatorRun.creatorReport.authoritativeFindings)
    if ($creatorRun.status -ne "COMPLETED" `
            -or $creatorRun.creatorReport.mode -ne "CREATOR_DRAFT_REVIEW" `
            -or $creatorRun.safeErrorCode `
            -or $authoritativeFindings.Count -ne 1 `
            -or $authoritativeFindings[0].code -ne "CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED" `
            -or $creatorRun.answer -ne $creatorRun.creatorReport.summary) {
        $findingCodes = @($authoritativeFindings | ForEach-Object { $_.code }) -join ","
        $answerMatchesSummary = $creatorRun.answer -ceq $creatorRun.creatorReport.summary
        $reportMode = if ($creatorRun.creatorReport) { $creatorRun.creatorReport.mode } else { "none" }
        throw "Persistent Creator smoke failed (status=$($creatorRun.status); safeErrorCode=$($creatorRun.safeErrorCode); reportMode=$reportMode; findingCodes=$findingCodes; answerMatchesSummary=$answerMatchesSummary)."
    }
    Write-Output "PASS persistent Creator run called Java validation over HTTP and kept its probability finding authoritative."

    $operatorLogin = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" `
        -Body (@{ username = $operatorName; password = $operatorPassword } | ConvertTo-Json -Compress) `
        -TimeoutSec 10
    $operatorHeaders = @{ Authorization = "Bearer $($operatorLogin.data.token)" }
    $operatorSession = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8083/api/ai/v2/sessions" `
        -Headers $operatorHeaders -TimeoutSec 10
    try {
        $null = Invoke-WebRequest -Method Post `
            -Uri "http://127.0.0.1:8083/api/ai/v2/sessions/$($operatorSession.sessionId)/runs" `
            -Headers $operatorHeaders -ContentType "application/json" -Body $creatorRunBody -TimeoutSec 10
        throw "Agent accepted a persistent Creator review from an OPERATOR token."
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 403) { throw }
    }
    Write-Output "PASS persistent Creator run rejects OPERATOR role before run admission."

    $otherName = "p14-other-$suffix"
    $otherPassword = Get-RandomHex 24
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/register" `
        -ContentType "application/json" `
        -Body (@{ username = $otherName; password = $otherPassword } | ConvertTo-Json -Compress) `
        -TimeoutSec 10
    $otherLogin = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" `
        -Body (@{ username = $otherName; password = $otherPassword } | ConvertTo-Json -Compress) `
        -TimeoutSec 10
    $foreignHeaders = @{ Authorization = "Bearer $($otherLogin.data.token)" }
    try {
        $null = Invoke-WebRequest -Uri "http://127.0.0.1:8083/api/ai/v2/runs/$($accepted.runId)" `
            -Headers $foreignHeaders -TimeoutSec 10
        throw "Agent returned another user's run to a foreign owner."
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    }
    Write-Output "PASS Agent cross-owner run access is hidden (404)."

    Assert-RuntimePrivilegeDenied "pfx_campaign_runtime" $accounts.CampaignRuntime "prizeflux" `
        "CREATE TABLE pfx_runtime_ddl_probe (id INT)" "Campaign runtime cannot create tables"
    Assert-RuntimePrivilegeDenied "pfx_campaign_runtime" $accounts.CampaignRuntime "prizeflux" `
        "SELECT COUNT(*) FROM prizeflux_agent.agent_session" "Campaign runtime cannot read Agent schema"
    Assert-RuntimePrivilegeDenied "pfx_worker_runtime" $accounts.WorkerRuntime "prizeflux_worker" `
        "CREATE TABLE pfx_runtime_ddl_probe (id INT)" "Worker runtime cannot create tables"
    Assert-RuntimePrivilegeDenied "pfx_worker_runtime" $accounts.WorkerRuntime "prizeflux_worker" `
        "SELECT COUNT(*) FROM prizeflux.agent_campaign" "Worker runtime cannot read Campaign schema"
    Assert-RuntimePrivilegeDenied "pfx_agent_runtime" $accounts.AgentRuntime "prizeflux_agent" `
        "CREATE TABLE pfx_runtime_ddl_probe (id INT)" "Agent runtime cannot create tables"
    Assert-RuntimePrivilegeDenied "pfx_agent_runtime" $accounts.AgentRuntime "prizeflux_agent" `
        "SELECT COUNT(*) FROM prizeflux.app_user" "Agent runtime cannot read Campaign schema"

    $expiryName = "p14-exp-$suffix"
    $expiryPassword = Get-RandomHex 24
    $expiryBody = @{ username = $expiryName; password = $expiryPassword } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/register" `
        -ContentType "application/json" -Body $expiryBody -TimeoutSec 10
    $expiryLogin = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" -Body $expiryBody -TimeoutSec 10
    $expiryHeaders = @{ Authorization = "Bearer $($expiryLogin.data.token)" }
    # The isolated Campaign is started with a 20-second TTL. Wait past the known TTL
    # plus whole-second JWT precision instead of relying on JSON date-kind conversion.
    Start-Sleep -Seconds 25
    $expiredStatus = 200
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        try {
            $expiredResponse = Invoke-WebRequest -Uri "http://127.0.0.1:8081/api/auth/me" `
                -Headers $expiryHeaders -TimeoutSec 10
            $expiredStatus = [int]$expiredResponse.StatusCode
        } catch {
            if ($null -eq $_.Exception.Response) { throw }
            $expiredStatus = [int]$_.Exception.Response.StatusCode
        }
        if ($expiredStatus -eq 401) { break }
        if ($expiredStatus -ne 200) { throw "Expired-token probe returned an unexpected HTTP $expiredStatus." }
        Start-Sleep -Seconds 1
    }
    if ($expiredStatus -ne 401) { throw "Campaign accepted an expired JWT (HTTP $expiredStatus)." }
    Write-Output "PASS Campaign rejects a JWT after its configured expiry."

    foreach ($aiService in @(
            [pscustomobject]@{ Name = "Python Agent"; Process = $agentProcess; Port = 8083 },
            [pscustomobject]@{ Name = "Legacy Java AI"; Process = $aiProcess; Port = 8082 })) {
        $aiService.Process.Refresh()
        if (-not $aiService.Process.HasExited) {
            Stop-Process -Id $aiService.Process.Id -Force
            $aiService.Process.WaitForExit()
        }
        $null = $criticalProcesses.Remove($aiService.Process)
        Wait-LoopbackListenerClosed $aiService.Port $aiService.Name
    }
    Write-Output "PASS Python Agent and legacy Java AI are stopped; their HTTP ports are closed."

    $operatorLogin = @{ username = $operatorName; password = $operatorPassword } | ConvertTo-Json -Compress
    $operatorSession = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" -Body $operatorLogin -TimeoutSec 10
    $operatorHeaders = @{ Authorization = "Bearer $($operatorSession.data.token)" }
    $drawUserLogin = @{ username = $userName; password = $userPassword } | ConvertTo-Json -Compress
    $drawUserSession = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" `
        -ContentType "application/json" -Body $drawUserLogin -TimeoutSec 10
    $drawUserHeaders = @{ Authorization = "Bearer $($drawUserSession.data.token)" }

    $drawCampaignId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $drawPrizeId = $drawCampaignId + 1
    $drawNow = [DateTimeOffset]::UtcNow
    $drawCampaign = @{
        id = $drawCampaignId; name = "AI outage isolation smoke"
        startsAt = $drawNow.AddMinutes(-1).ToString("o"); endsAt = $drawNow.AddHours(1).ToString("o")
    } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/campaigns" `
        -Headers $operatorHeaders -ContentType "application/json" -Body $drawCampaign -TimeoutSec 10
    $drawConfiguration = @{
        dailyLimit = 5
        prizes = @(@{ id = $drawPrizeId; name = "AI outage smoke prize"; totalStock = 2; probability = 1.0 })
    } | ConvertTo-Json -Depth 5 -Compress
    $null = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:8081/api/campaigns/$drawCampaignId/configuration" `
        -Headers $operatorHeaders -ContentType "application/json" -Body $drawConfiguration -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/campaigns/$drawCampaignId/publish" `
        -Headers $operatorHeaders -TimeoutSec 10

    $instantRequest = @{
        campaignId = $drawCampaignId; requestKey = "ai-outage-now-$suffix"
    } | ConvertTo-Json -Compress
    $instantDraw = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/draws" `
        -Headers $drawUserHeaders -ContentType "application/json" -Body $instantRequest -TimeoutSec 10
    if ($instantDraw.data.outcome -ne "WON" -or $instantDraw.data.prizeId -ne $drawPrizeId) {
        throw "Instant draw did not complete successfully while AI services were stopped."
    }

    $queueKey = "ai-outage-q-$suffix"
    $queueRequest = @{
        campaignId = $drawCampaignId; requestKey = $queueKey
    } | ConvertTo-Json -Compress
    $queuedDraw = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/draw-queue" `
        -Headers $drawUserHeaders -ContentType "application/json" -Body $queueRequest -TimeoutSec 10
    if ($queuedDraw.data.status -ne "QUEUED") {
        throw "Queue draw was not accepted while AI services were stopped."
    }
    $queueResult = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Seconds 1
        $queueResult = Invoke-RestMethod -Uri "http://127.0.0.1:8081/api/me/draw-queue/$queueKey" `
            -Headers $drawUserHeaders -TimeoutSec 10
        if ($queueResult.data.status -in @("COMPLETED", "REJECTED", "MANUAL_REVIEW")) { break }
    }
    if ($queueResult.data.status -ne "COMPLETED" -or $queueResult.data.result.outcome -ne "WON" `
            -or $queueResult.data.result.prizeId -ne $drawPrizeId) {
        throw "Queue draw did not complete successfully while AI services were stopped."
    }
    $queueOutboxState = Invoke-MySqlRuntimeScalar $accounts.CampaignRuntime `
        "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_type = 'DrawQueued' AND aggregate_id = $drawCampaignId"
    if ($queueOutboxState -notmatch '^SENT:[1-9][0-9]*$') {
        throw "Queued draw did not publish its durable Kafka notification (outbox=$queueOutboxState)."
    }
    $durableDrawCount = Invoke-MySqlRuntimeScalar $accounts.CampaignRuntime `
        "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $drawCampaignId"
    if ($durableDrawCount -ne "2") {
        throw "AI outage draw scenario did not persist exactly two draws."
    }
    $remainingStock = Invoke-MySqlRuntimeScalar $accounts.CampaignRuntime `
        "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = $drawCampaignId"
    if ($remainingStock -ne "0") {
        throw "AI outage draw scenario did not consume exactly two units of inventory."
    }
    Write-Output "PASS with both AI services stopped: instant draw WON; DrawQueued Outbox SENT through Kafka; queued draw COMPLETED/WON; two durable draws and inventory reached zero."

    $sourceMysqlContainer = (& docker compose --project-name $projectName --file $composeFile ps --quiet mysql | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($sourceMysqlContainer)) { throw "Could not identify the disposable source MySQL container." }
    $workerSuccessCount = "0"
    $workerDeadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $workerDeadline) {
        $workerSuccessCount = Invoke-ContainerMySqlScalar $sourceMysqlContainer "pfx_worker_runtime" `
            $accounts.WorkerRuntime "prizeflux_worker" `
            "SELECT COUNT(*) FROM fulfillment WHERE status = 'SUCCEEDED' AND campaign_id = $drawCampaignId"
        if ([int]$workerSuccessCount -ge 2) { break }
        Start-Sleep -Milliseconds 500
    }
    if ([int]$workerSuccessCount -ne 2) {
        throw "Expected two successful simulated Worker ledger rows before backup; found $workerSuccessCount."
    }

    foreach ($serviceProcess in @($workerProcess, $campaignProcess)) {
        $serviceProcess.Refresh()
        if (-not $serviceProcess.HasExited) {
            Stop-Process -Id $serviceProcess.Id -Force
            $serviceProcess.WaitForExit()
        }
        $null = $criticalProcesses.Remove($serviceProcess)
    }
    Write-Output "PASS Campaign and Worker writers are stopped before the logical backup snapshot."

    $agentCompletedCount = Invoke-ContainerMySqlScalar $sourceMysqlContainer "pfx_agent_runtime" `
        $accounts.AgentRuntime "prizeflux_agent" `
        "SELECT COUNT(*) FROM agent_run WHERE status = 'COMPLETED'"
    if ([int]$agentCompletedCount -lt 1) { throw "The disposable Agent schema has no completed run to back up." }

    $restorePassword = Get-RandomHex
    $restoreVolumeName = "pfx-restore-$runId"
    $restoreContainerName = "pfx-restore-mysql-$runId"
    $null = & docker volume inspect $restoreVolumeName 2>$null
    if ($LASTEXITCODE -eq 0) { throw "The unique restore-volume name already exists; refusing to reuse it." }
    $null = & docker container inspect $restoreContainerName 2>$null
    if ($LASTEXITCODE -eq 0) { throw "The unique restore-container name already exists; refusing to reuse it." }

    $createdVolume = & docker volume create --label "prizeflux.smoke.run=$runId" $restoreVolumeName
    if ($LASTEXITCODE -ne 0 -or $createdVolume.Trim() -ne $restoreVolumeName) {
        throw "Could not create the uniquely labeled disposable restore volume."
    }
    $restoreVolumeCreated = $true
    $volumeMetadata = (& docker volume inspect $restoreVolumeName | Out-String) | ConvertFrom-Json
    if ($volumeMetadata[0].Name -ne $restoreVolumeName -or `
            (Get-DockerLabelValue $volumeMetadata[0].Labels "prizeflux.smoke.run") -ne $runId) {
        throw "Restore-volume identity check failed; refusing to use the volume."
    }

    $startedRestoreContainer = & docker run --detach --name $restoreContainerName `
        --label "prizeflux.smoke.run=$runId" `
        --mount "type=volume,source=$restoreVolumeName,target=/var/lib/mysql" `
        --env "MYSQL_ROOT_PASSWORD=$restorePassword" mysql:8.4
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace([string]$startedRestoreContainer)) {
        throw "Could not start MySQL against the fresh restore volume."
    }
    $restoreContainerCreated = $true
    $restoreReadyDeadline = [DateTime]::UtcNow.AddSeconds(120)
    $restoreReady = $false
    while ([DateTime]::UtcNow -lt $restoreReadyDeadline) {
        & docker exec $restoreContainerName mysqladmin ping -h 127.0.0.1 --silent *> $null
        if ($LASTEXITCODE -eq 0) { $restoreReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $restoreReady) { throw "MySQL did not become ready on the fresh restore volume." }

    & docker exec --env "MYSQL_PWD=$mysqlRootPassword" $sourceMysqlContainer `
        mysqldump --user=root --single-transaction --routines --events --triggers `
        --databases prizeflux prizeflux_worker prizeflux_agent |
        & docker exec --interactive --env "MYSQL_PWD=$restorePassword" $restoreContainerName mysql --user=root
    if ($LASTEXITCODE -ne 0) { throw "Logical backup restore into the fresh volume failed." }

    $restoredDrawCount = Invoke-ContainerMySqlScalar $restoreContainerName "root" $restorePassword `
        "prizeflux" "SELECT COUNT(*) FROM draw_result WHERE campaign_id = $drawCampaignId"
    $restoredQueueState = Invoke-ContainerMySqlScalar $restoreContainerName "root" $restorePassword `
        "prizeflux" "SELECT status FROM draw_queue_request WHERE request_key = '$queueKey'"
    $restoredStock = Invoke-ContainerMySqlScalar $restoreContainerName "root" $restorePassword `
        "prizeflux" "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = $drawCampaignId"
    $restoredFulfillmentCount = Invoke-ContainerMySqlScalar $restoreContainerName "root" $restorePassword `
        "prizeflux_worker" "SELECT COUNT(*) FROM fulfillment WHERE status = 'SUCCEEDED' AND campaign_id = $drawCampaignId"
    $restoredAgentRunCount = Invoke-ContainerMySqlScalar $restoreContainerName "root" $restorePassword `
        "prizeflux_agent" "SELECT COUNT(*) FROM agent_run WHERE status = 'COMPLETED'"
    if ($restoredDrawCount -ne "2" -or $restoredQueueState -ne "COMPLETED" -or $restoredStock -ne "0" `
            -or $restoredFulfillmentCount -ne "2" -or [int]$restoredAgentRunCount -lt 1) {
        throw "Restored business-data verification failed (draws=$restoredDrawCount; queue=$restoredQueueState; stock=$restoredStock; fulfillments=$restoredFulfillmentCount; agentRuns=$restoredAgentRunCount)."
    }
    Write-Output "PASS MySQL logical backup restored into a fresh labeled volume; Campaign, Worker and Agent rows matched."

    foreach ($process in $criticalProcesses) {
        $process.Refresh()
        if ($process.HasExited) { throw "A smoke process exited unexpectedly before completion." }
    }
    Write-Output "P14-C process smoke and P14-F AI-outage/backup-restore checks passed. Test services will now be stopped and removed."
    Write-Output "Sanitized process logs: $logDirectory"
} finally {
    foreach ($process in $managedProcesses) {
        $process.Refresh()
        if (-not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null
        }
        $process.Dispose()
    }
    if ($composeStarted) {
        try { Invoke-Compose @("down", "--remove-orphans") } catch { Write-Warning "Could not stop only the disposable smoke Compose project $projectName." }
    }
    if ($restoreContainerCreated) {
        $containerMetadata = (& docker container inspect $restoreContainerName 2>$null | Out-String) | ConvertFrom-Json
        if ($LASTEXITCODE -eq 0 -and $containerMetadata[0].Name -eq "/$restoreContainerName" `
                -and (Get-DockerLabelValue $containerMetadata[0].Config.Labels "prizeflux.smoke.run") -eq $runId) {
            & docker container rm --force $restoreContainerName | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove the verified disposable restore container $restoreContainerName." }
        } else {
            Write-Warning "Restore container identity could not be verified; it was left untouched."
        }
    }
    if ($restoreVolumeCreated) {
        $volumeMetadata = (& docker volume inspect $restoreVolumeName 2>$null | Out-String) | ConvertFrom-Json
        if ($LASTEXITCODE -eq 0 -and $volumeMetadata[0].Name -eq $restoreVolumeName `
                -and (Get-DockerLabelValue $volumeMetadata[0].Labels "prizeflux.smoke.run") -eq $runId) {
            & docker volume rm $restoreVolumeName | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove the verified disposable restore volume $restoreVolumeName." }
        } else {
            Write-Warning "Restore volume identity could not be verified; it was left untouched."
        }
    }
    foreach ($name in $environmentTouched) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
