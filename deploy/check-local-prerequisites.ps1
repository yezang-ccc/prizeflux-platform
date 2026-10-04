[CmdletBinding()]
param(
    [Parameter()]
    [string] $EnvFile = (Join-Path $PSScriptRoot ".env")
)

$ErrorActionPreference = "Stop"
$problems = [System.Collections.Generic.List[string]]::new()
$values = @{}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    $problems.Add("Environment file was not found: $EnvFile")
} else {
    $resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
    foreach ($line in Get-Content -LiteralPath $resolvedEnvFile) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $problems.Add("Environment file contains an unsupported line format.")
            continue
        }
        $key = $Matches[1]
        $value = $Matches[2]
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
}

# Docker Compose gives the process environment precedence over --env-file.
foreach ($key in @("PFX_DB_ROOT_PASSWORD", "PFX_DB_PASSWORD", "PFX_DB_PORT", "PFX_REDIS_PORT")) {
    $processValue = [Environment]::GetEnvironmentVariable($key)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        $values[$key] = $processValue
    }
}

# The user explicitly authorized reusing these existing values for a new,
# isolated PrizeFlux schema. Compose supports the same aliases directly.
foreach ($alias in @(
    @{ Target = "PFX_DB_ROOT_PASSWORD"; Source = "MYSQL_ROOT_PASSWORD" },
    @{ Target = "PFX_DB_PASSWORD"; Source = "MYSQL_PASSWORD" }
)) {
    if (-not $values.ContainsKey($alias.Target) -or
        [string]::IsNullOrWhiteSpace([string]$values[$alias.Target])) {
        $aliasValue = [Environment]::GetEnvironmentVariable($alias.Source)
        if ([string]::IsNullOrWhiteSpace($aliasValue) -and $values.ContainsKey($alias.Source)) {
            $aliasValue = [string]$values[$alias.Source]
        }
        if (-not [string]::IsNullOrWhiteSpace($aliasValue)) {
            $values[$alias.Target] = $aliasValue
        }
    }
}

foreach ($key in @("PFX_DB_ROOT_PASSWORD", "PFX_DB_PASSWORD")) {
    if (-not $values.ContainsKey($key) -or
        [string]::IsNullOrWhiteSpace([string]$values[$key]) -or
        ([string]$values[$key]).StartsWith("replace-with-", [StringComparison]::OrdinalIgnoreCase)) {
        $problems.Add("Required local setting is missing or still a template value: $key")
    }
}

function Get-ValidatedPort([string] $Name, [int] $DefaultPort) {
    if (-not $values.ContainsKey($Name)) { return $DefaultPort }
    $parsedPort = 0
    if (-not [int]::TryParse([string]$values[$Name], [ref]$parsedPort) -or
        $parsedPort -lt 1 -or $parsedPort -gt 65535) {
        $problems.Add("Local port setting must be an integer from 1 to 65535: $Name")
        return $null
    }
    return $parsedPort
}

function Test-LoopbackPortAvailable([int] $Port) {
    $listener = $null
    try {
        $address = [System.Net.IPAddress]::Parse("127.0.0.1")
        $listener = [System.Net.Sockets.TcpListener]::new($address, $Port)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $listener) { $listener.Stop() }
    }
}

$dbPort = Get-ValidatedPort "PFX_DB_PORT" 3306
$redisPort = Get-ValidatedPort "PFX_REDIS_PORT" 6379
foreach ($entry in @(@("PFX_DB_PORT", $dbPort), @("PFX_REDIS_PORT", $redisPort))) {
    $name = [string]$entry[0]
    $port = $entry[1]
    if ($null -eq $port) { continue }
    if (-not (Test-LoopbackPortAvailable ([int]$port))) {
        $problems.Add("127.0.0.1:$port is already in use; choose another host port in deploy/.env for $name.")
    }
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    $problems.Add("Docker CLI was not found on PATH.")
} else {
    $dockerVersion = & $docker.Source info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($dockerVersion | Out-String).Trim())) {
        $problems.Add("Docker Engine is not reachable. Start Docker Desktop, then retry.")
    } else {
        Write-Output "Docker Engine is reachable (version $(($dockerVersion | Out-String).Trim()))."
    }
}

if ($problems.Count -eq 0) {
    $composeFile = Join-Path $PSScriptRoot "compose.foundation.yml"
    $null = & docker compose --env-file $resolvedEnvFile -f $composeFile config --quiet 2>$null
    if ($LASTEXITCODE -ne 0) {
        $problems.Add("Docker Compose rejected the foundation configuration. Check required setting names and compose syntax.")
    }
}

if ($problems.Count -gt 0) {
    foreach ($problem in $problems) { Write-Error $problem -ErrorAction Continue }
    exit 1
}

Write-Output "Local foundation prerequisites are satisfied. No containers were started or changed."
