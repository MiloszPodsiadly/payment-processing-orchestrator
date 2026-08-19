$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$examplePath = Join-Path $root ".env.example"
$envPath = Join-Path $root ".env"

if (-not (Test-Path -LiteralPath $examplePath)) {
    throw ".env.example not found at $examplePath"
}

if (Test-Path -LiteralPath $envPath) {
    Write-Output ".env already exists; leaving it unchanged."
    exit 0
}

Copy-Item -LiteralPath $examplePath -Destination $envPath
Write-Output "Created .env from .env.example."

