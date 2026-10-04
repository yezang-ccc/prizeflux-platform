$ErrorActionPreference = "Stop"

$environmentPath = Join-Path $PSScriptRoot ".env.product"
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
    throw "Create deploy/.env.product with new-product-env.ps1 and start the local-full stack first."
}

$username = Read-Host "Choose an OPERATOR username (3-64 lowercase letters, digits, ., _, -)"
if ($username -notmatch "^[a-z0-9._-]{3,64}$") {
    throw "The OPERATOR username format is invalid."
}

$securePassword = Read-Host "Choose the OPERATOR password (12-128 characters)" -AsSecureString
$passwordPointer = [IntPtr]::Zero
$password = $null
try {
    $passwordPointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ($password.Length -lt 12 -or $password.Length -gt 128) {
        throw "The OPERATOR password length is invalid."
    }

    $usernameVariable = "PFX_BOOTSTRAP_OPERATOR_USERNAME"
    $passwordVariable = "PFX_BOOTSTRAP_OPERATOR_PASSWORD"
    $oldUsername = [Environment]::GetEnvironmentVariable($usernameVariable, "Process")
    $oldPassword = [Environment]::GetEnvironmentVariable($passwordVariable, "Process")
    try {
        [Environment]::SetEnvironmentVariable($usernameVariable, $username, "Process")
        [Environment]::SetEnvironmentVariable($passwordVariable, $password, "Process")
        $composeArguments = @(
            "--project-name", "prizeflux",
            "--env-file", $environmentPath,
            "--file", (Join-Path $PSScriptRoot "compose.product.yml"),
            "--profile", "local-full",
            "--profile", "operator-bootstrap",
            "run", "--rm", "campaign-bootstrap"
        )
        & docker compose @composeArguments
        if ($LASTEXITCODE -ne 0) {
            throw "OPERATOR bootstrap failed. It may already exist; the script did not save the supplied password."
        }
    } finally {
        [Environment]::SetEnvironmentVariable($usernameVariable, $oldUsername, "Process")
        [Environment]::SetEnvironmentVariable($passwordVariable, $oldPassword, "Process")
    }
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $password = $null
    $securePassword.Dispose()
}

Write-Output "OPERATOR was initialized once. Credentials were not written to a file; keep the password you entered."
