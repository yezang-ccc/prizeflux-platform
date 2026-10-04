$ErrorActionPreference = "Stop"

$templatePath = Join-Path $PSScriptRoot "product.env.example"
$targetPath = Join-Path $PSScriptRoot ".env.product"
if (Test-Path -LiteralPath $targetPath) {
    throw "deploy/.env.product already exists; it was left unchanged."
}

$content = [System.IO.File]::ReadAllText($templatePath)
$secretNames = @(
    "PFX_CONTAINER_MYSQL_ROOT_PASSWORD",
    "PFX_CAMPAIGN_RUNTIME_PASSWORD",
    "PFX_CAMPAIGN_MIGRATION_PASSWORD",
    "PFX_WORKER_RUNTIME_PASSWORD",
    "PFX_WORKER_MIGRATION_PASSWORD",
    "PFX_AGENT_RUNTIME_PASSWORD",
    "PFX_AGENT_MIGRATION_PASSWORD",
    "PFX_PRODUCT_JWT_SECRET"
)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($name in $secretNames) {
        $placeholder = "${name}=__GENERATE_64_HEX__"
        if (-not $content.Contains($placeholder)) {
            throw "The local environment template is missing a required secret placeholder."
        }
        $bytes = [byte[]]::new(32)
        $random.GetBytes($bytes)
        $secret = [Convert]::ToHexString($bytes).ToLowerInvariant()
        $content = $content.Replace($placeholder, "${name}=${secret}")
    }
} finally {
    $random.Dispose()
}

$encoding = [System.Text.UTF8Encoding]::new($false)
$stream = [System.IO.File]::Open(
    $targetPath,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::None
)
try {
    $writer = [System.IO.StreamWriter]::new($stream, $encoding)
    try {
        $writer.Write($content)
    } finally {
        $writer.Dispose()
    }
} finally {
    $stream.Dispose()
}

Write-Output "Created deploy/.env.product with fresh local-only secrets; secret values were not printed."
