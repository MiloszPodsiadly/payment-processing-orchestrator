param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Command
)

$ErrorActionPreference = "Stop"

function Require-Command {
    param([string[]] $ProvidedCommand)

    if ($ProvidedCommand.Count -eq 0) {
        throw "ERROR: command required. Usage: .\scripts\windows\run-with-env.ps1 -- <command> [args...]"
    }

    if ($ProvidedCommand[0] -eq "--" -and $ProvidedCommand.Count -eq 1) {
        throw "ERROR: command required. Usage: .\scripts\windows\run-with-env.ps1 -- <command> [args...]"
    }
}

Require-Command -ProvidedCommand $Command

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$examplePath = Join-Path $root ".env.example"
$envPath = if ($env:PAYMENT_ENV_FILE) { $env:PAYMENT_ENV_FILE } else { Join-Path $root ".env" }

if (-not (Test-Path -LiteralPath $examplePath)) {
    throw ".env.example not found at $examplePath"
}

if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath $examplePath -Destination $envPath
    Write-Output "Created .env from .env.example."
}

Get-Content -LiteralPath $envPath | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith("#")) {
        return
    }

    $separator = $line.IndexOf("=")
    if ($separator -le 0) {
        throw "Invalid .env line: $line"
    }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()

    if ($name -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
        throw "Invalid environment variable name: $name"
    }

    Set-Item -LiteralPath "Env:$name" -Value $value
}

if ($Command[0] -eq "--") {
    $Command = $Command[1..($Command.Count - 1)]
}

$arguments = @()
if ($Command.Count -gt 1) {
    $arguments = $Command[1..($Command.Count - 1)]
}

$ErrorActionPreference = "Continue"
& $Command[0] @arguments
exit $LASTEXITCODE
