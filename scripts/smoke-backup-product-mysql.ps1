[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$image = 'mysql:8.4'
$imageId = & docker image inspect $image --format '{{.Id}}' 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace([string]$imageId)) {
    throw "The local $image image is required; this smoke test will not pull images."
}

$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$projectName = "pfx-backup-smoke-$runId"
$sourceVolume = "$projectName-source"
$sourceContainer = "$projectName-mysql"
$restoreVolume = "$projectName-restore"
$restoreContainer = "$projectName-restore-mysql"
$rootPassword = [Guid]::NewGuid().ToString('N')
$backupPath = Join-Path $env:TEMP "$projectName.sql"
$createdSourceVolume = $false
$createdSourceContainer = $false
$createdRestoreVolume = $false
$createdRestoreContainer = $false

function Wait-ForHealthyContainer([string]$ContainerId) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $health = [string](& docker inspect --format '{{.State.Health.Status}}' $ContainerId 2>$null)
        if ($LASTEXITCODE -eq 0 -and $health -eq 'healthy') { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for an isolated MySQL smoke container to become healthy.'
}

function Remove-VerifiedTestContainer([string]$Name, [string]$ExpectedRunId) {
    $identity = [string](& docker inspect --format '{{.Name}}|{{ index .Config.Labels "pfx.backup.test" }}' $Name 2>$null)
    if ($LASTEXITCODE -eq 0 -and $identity -eq "/$Name|$ExpectedRunId") {
        & docker rm --force $Name *> $null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove verified smoke container '$Name'." }
    } elseif ($LASTEXITCODE -eq 0) {
        Write-Warning "Container '$Name' identity did not match this run; it was left untouched."
    }
}

function Remove-VerifiedTestVolume([string]$Name, [string]$ExpectedRunId) {
    $identity = [string](& docker volume inspect --format '{{.Name}}|{{ index .Labels "pfx.backup.test" }}' $Name 2>$null)
    if ($LASTEXITCODE -eq 0 -and $identity -eq "$Name|$ExpectedRunId") {
        & docker volume rm $Name *> $null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove verified smoke volume '$Name'." }
    } elseif ($LASTEXITCODE -eq 0) {
        Write-Warning "Volume '$Name' identity did not match this run; it was left untouched."
    }
}

try {
    foreach ($name in @($sourceVolume, $sourceContainer, $restoreVolume, $restoreContainer)) {
        if ($name.EndsWith('-mysql')) {
            $null = & docker container inspect $name 2>$null
        } else {
            $null = & docker volume inspect $name 2>$null
        }
        if ($LASTEXITCODE -eq 0) { throw "Unique smoke-test resource '$name' already exists; refusing to reuse it." }
    }
    if (Test-Path -LiteralPath $backupPath) { throw 'Generated smoke backup path unexpectedly exists; refusing to overwrite it.' }

    $created = [string](& docker volume create `
        --label "com.docker.compose.project=$projectName" `
        --label 'com.docker.compose.volume=prizeflux_product_mysql_data' `
        --label "pfx.backup.test=$runId" $sourceVolume)
    if ($LASTEXITCODE -ne 0 -or $created.Trim() -ne $sourceVolume) { throw 'Could not create the unique source smoke volume.' }
    $createdSourceVolume = $true

    $sourceId = [string](& docker run --detach --network none --name $sourceContainer `
        --label "com.docker.compose.project=$projectName" `
        --label 'com.docker.compose.service=mysql' `
        --label "pfx.backup.test=$runId" `
        --mount "type=volume,source=$sourceVolume,target=/var/lib/mysql" `
        --env "MYSQL_ROOT_PASSWORD=$rootPassword" `
        --health-cmd 'mysqladmin ping -h 127.0.0.1 -uroot --silent' `
        --health-interval 2s --health-timeout 3s --health-retries 40 $image)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sourceId)) { throw 'Could not start the isolated source MySQL container.' }
    $sourceId = $sourceId.Trim()
    $createdSourceContainer = $true
    Wait-ForHealthyContainer $sourceId

    $fixtureSql = 'CREATE DATABASE prizeflux; CREATE DATABASE prizeflux_worker; CREATE DATABASE prizeflux_agent; CREATE TABLE prizeflux.backup_probe (id INT PRIMARY KEY, marker VARCHAR(64)) ENGINE=InnoDB; INSERT INTO prizeflux.backup_probe VALUES (1, ''snapshot-ok'');'
    & docker exec --env "MYSQL_PWD=$rootPassword" $sourceId mysql --user=root --execute=$fixtureSql *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize isolated backup fixture data.' }

    & (Join-Path $PSScriptRoot '..\deploy\backup-product-mysql.ps1') `
        -OutputPath $backupPath -ComposeProjectName $projectName -ExpectedVolumeName $sourceVolume
    if ($LASTEXITCODE -ne 0) { throw 'The product MySQL backup script failed against the isolated source volume.' }
    if (-not (Select-String -LiteralPath $backupPath -SimpleMatch 'snapshot-ok' -Quiet)) {
        throw 'The produced SQL dump does not contain the isolated fixture marker.'
    }

    $created = [string](& docker volume create --label "pfx.backup.test=$runId" $restoreVolume)
    if ($LASTEXITCODE -ne 0 -or $created.Trim() -ne $restoreVolume) { throw 'Could not create the unique restore smoke volume.' }
    $createdRestoreVolume = $true

    $restoreId = [string](& docker run --detach --network none --name $restoreContainer `
        --label "pfx.backup.test=$runId" `
        --mount "type=volume,source=$restoreVolume,target=/var/lib/mysql" `
        --env MYSQL_ALLOW_EMPTY_PASSWORD=yes `
        --health-cmd 'mysqladmin ping -h 127.0.0.1 -uroot --silent' `
        --health-interval 2s --health-timeout 3s --health-retries 40 $image)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($restoreId)) { throw 'Could not start the isolated restore MySQL container.' }
    $restoreId = $restoreId.Trim()
    $createdRestoreContainer = $true
    Wait-ForHealthyContainer $restoreId

    Get-Content -LiteralPath $backupPath | & docker exec --interactive $restoreId mysql --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Could not restore the generated SQL dump into the fresh isolated volume.' }
    $restoredMarker = [string](& docker exec $restoreId mysql --user=root --batch --skip-column-names `
        --execute='SELECT marker FROM prizeflux.backup_probe WHERE id = 1')
    if ($LASTEXITCODE -ne 0 -or $restoredMarker.Trim() -ne 'snapshot-ok') {
        throw 'The restored fixture row did not match the original value.'
    }
    $restoredSchemaCount = [string](& docker exec $restoreId mysql --user=root --batch --skip-column-names `
        --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('prizeflux', 'prizeflux_worker', 'prizeflux_agent')")
    if ($LASTEXITCODE -ne 0 -or $restoredSchemaCount.Trim() -ne '3') { throw 'The restored schema set is incomplete.' }

    Write-Output 'PASS guarded product-volume backup produced SQL and restored to a separate fresh isolated volume.'
    Write-Output 'No product Compose service, existing named volume, port mapping, or external network was used.'
} finally {
    if (Test-Path -LiteralPath $backupPath) { Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue }
    if ($createdRestoreContainer) { Remove-VerifiedTestContainer $restoreContainer $runId }
    if ($createdRestoreVolume) { Remove-VerifiedTestVolume $restoreVolume $runId }
    if ($createdSourceContainer) { Remove-VerifiedTestContainer $sourceContainer $runId }
    if ($createdSourceVolume) { Remove-VerifiedTestVolume $sourceVolume $runId }
}
