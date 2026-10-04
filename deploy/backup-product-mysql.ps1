[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [string]$ComposeProjectName = 'prizeflux',
    [string]$ExpectedVolumeName = 'prizeflux_product_mysql_data'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is required.'
}
if ($ComposeProjectName -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') {
    throw 'ComposeProjectName contains unsupported characters.'
}
if ($ExpectedVolumeName -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') {
    throw 'ExpectedVolumeName contains unsupported characters.'
}
if (-not [IO.Path]::IsPathRooted($OutputPath)) {
    throw 'OutputPath must be an absolute path outside the repository.'
}

$backupPath = [IO.Path]::GetFullPath($OutputPath)
if ([IO.Path]::GetExtension($backupPath) -ne '.sql') {
    throw 'OutputPath must end in .sql; the dump is plain-text and is not encrypted.'
}
$relativePath = [IO.Path]::GetRelativePath($repoRoot, $backupPath)
if (-not [IO.Path]::IsPathRooted($relativePath) -and $relativePath -ne '..' `
        -and -not $relativePath.StartsWith('..' + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
    throw 'Database dumps must be stored outside the repository.'
}
if (Test-Path -LiteralPath $backupPath) {
    throw 'OutputPath already exists; refusing to overwrite a previous backup.'
}

$backupDirectory = [IO.Path]::GetDirectoryName($backupPath)
$null = [IO.Directory]::CreateDirectory($backupDirectory)
$backupDirectory = (Resolve-Path -LiteralPath $backupDirectory).Path
$backupPath = Join-Path $backupDirectory ([IO.Path]::GetFileName($backupPath))
$relativePath = [IO.Path]::GetRelativePath($repoRoot, $backupPath)
if (-not [IO.Path]::IsPathRooted($relativePath) -and $relativePath -ne '..' `
        -and -not $relativePath.StartsWith('..' + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
    throw 'The resolved backup directory points inside the repository; database dumps must stay outside it.'
}
if (Test-Path -LiteralPath $backupPath) {
    throw 'The resolved OutputPath already exists; refusing to overwrite a previous backup.'
}
$partialPath = "$backupPath.partial-$([Guid]::NewGuid().ToString('N'))"

try {
    $containerIds = @(
        & docker ps `
            --filter "label=com.docker.compose.project=$ComposeProjectName" `
            --filter 'label=com.docker.compose.service=mysql' `
            --format '{{.ID}}' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($LASTEXITCODE -ne 0) { throw 'Could not query Docker for the product MySQL container.' }
    if ($containerIds.Count -ne 1) {
        throw "Expected exactly one running MySQL service for Compose project '$ComposeProjectName'; found $($containerIds.Count)."
    }
    $containerId = [string]$containerIds[0]

    $containerIdentity = [string](& docker inspect --format `
        '{{ index .Config.Labels "com.docker.compose.project" }}|{{ index .Config.Labels "com.docker.compose.service" }}|{{ .State.Status }}|{{ if .State.Health }}{{ .State.Health.Status }}{{ end }}' `
        $containerId)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the selected MySQL container.' }
    if ($containerIdentity -ne "$ComposeProjectName|mysql|running|healthy") {
        throw 'The selected MySQL container identity/state is not the expected healthy Compose service; no backup was started.'
    }

    $mountIdentity = [string](& docker inspect --format `
        '{{ range .Mounts }}{{ if eq .Destination "/var/lib/mysql" }}{{ .Type }}|{{ .Name }}{{ end }}{{ end }}' `
        $containerId)
    if ($LASTEXITCODE -ne 0 -or $mountIdentity -ne "volume|$ExpectedVolumeName") {
        throw 'The MySQL data mount does not match the explicitly expected named volume; refusing to dump an unexpected database.'
    }

    $volumeIdentity = [string](& docker volume inspect --format `
        '{{ .Name }}|{{ index .Labels "com.docker.compose.project" }}|{{ index .Labels "com.docker.compose.volume" }}' `
        $ExpectedVolumeName)
    if ($LASTEXITCODE -ne 0 -or $volumeIdentity -ne "$ExpectedVolumeName|$ComposeProjectName|prizeflux_product_mysql_data") {
        throw 'The named volume labels do not match the selected product Compose project; refusing to continue.'
    }

    $schemaCountCommand = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --user=root --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN (''prizeflux'', ''prizeflux_worker'', ''prizeflux_agent'')"'
    $schemaCount = [string](& docker exec $containerId sh -eu -c $schemaCountCommand)
    if ($LASTEXITCODE -ne 0 -or $schemaCount.Trim() -ne '3') {
        throw 'The expected Campaign, Worker, and Agent schemas are not all available; no backup was written.'
    }

    $nonInnoDbCommand = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --user=root --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN (''prizeflux'', ''prizeflux_worker'', ''prizeflux_agent'') AND table_type = ''BASE TABLE'' AND engine <> ''InnoDB''"'
    $nonInnoDbCount = [string](& docker exec $containerId sh -eu -c $nonInnoDbCommand)
    if ($LASTEXITCODE -ne 0 -or $nonInnoDbCount.Trim() -ne '0') {
        throw 'Could not establish that all application tables use InnoDB; refusing a potentially inconsistent single-transaction dump.'
    }

    $dumpCommand = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump --user=root --single-transaction --quick --routines --events --triggers --hex-blob --default-character-set=utf8mb4 --databases prizeflux prizeflux_worker prizeflux_agent'
    & docker exec $containerId sh -eu -c $dumpCommand > $partialPath
    if ($LASTEXITCODE -ne 0) { throw 'mysqldump failed; the partial file will be removed.' }

    $partialItem = Get-Item -LiteralPath $partialPath
    if ($partialItem.Length -lt 100) { throw 'The dump output is unexpectedly small; refusing to publish it as a backup.' }
    [IO.File]::Move($partialPath, $backupPath)

    $backupItem = Get-Item -LiteralPath $backupPath
    $hash = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash
    [pscustomobject]@{
        Result = 'PASS'
        BackupPath = $backupPath
        Bytes = $backupItem.Length
        Sha256 = $hash
        Schemas = @('prizeflux', 'prizeflux_worker', 'prizeflux_agent')
        SourceVolume = $ExpectedVolumeName
        Encrypted = $false
        CredentialsOrDumpContentsPrinted = $false
    } | Format-List
}
finally {
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
    }
}
