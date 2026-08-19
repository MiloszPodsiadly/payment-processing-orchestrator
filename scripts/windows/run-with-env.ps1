param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Command
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$examplePath = Join-Path $root ".env.example"
$envPath = Join-Path $root ".env"

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

if ($Command.Count -eq 0) {
    Get-ChildItem Env:PAYMENT_* | Sort-Object Name | ForEach-Object {
        Write-Output "$($_.Name)=$($_.Value)"
    }
    exit 0
}

if ($Command[0] -eq "--") {
    if ($Command.Count -eq 1) {
        throw "No command provided after --"
    }

    $Command = $Command[1..($Command.Count - 1)]
}

if ($Command.Count -eq 0) {
    throw "No command provided after --"
}

$arguments = @()
if ($Command.Count -gt 1) {
    $arguments = $Command[1..($Command.Count - 1)]
}

& $Command[0] @arguments
exit $LASTEXITCODE
