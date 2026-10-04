$ErrorActionPreference = 'Stop'

$deployDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$secretsDirectory = Join-Path $deployDirectory '.observability-secrets'
$secretNames = @(
    'campaign-metrics-password',
    'worker-metrics-password',
    'grafana-admin-password',
    'mysql-lock-metrics-password'
)

if (Test-Path -LiteralPath $secretsDirectory) {
    throw 'Secret directory already exists. Inspect it manually; this script never overwrites or reuses existing secret files.'
}

New-Item -ItemType Directory -Path $secretsDirectory | Out-Null
$currentUser = "$env:USERDOMAIN\$env:USERNAME"
& icacls.exe $secretsDirectory /inheritance:r /grant:r "${currentUser}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Could not restrict the local secret directory ACL; no secret values were generated.'
}

foreach ($secretName in $secretNames) {
    $bytes = New-Object -TypeName 'System.Byte[]' -ArgumentList 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $generator.GetBytes($bytes)
    $secretValue = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $secretPath = Join-Path $secretsDirectory $secretName
    [System.IO.File]::WriteAllText($secretPath, $secretValue, [System.Text.Encoding]::ASCII)
    [Array]::Clear($bytes, 0, $bytes.Length)
    $generator.Dispose()
    $secretValue = $null
}

Write-Host 'Created four local-only observability secrets. Values were not displayed; the directory is gitignored.'
