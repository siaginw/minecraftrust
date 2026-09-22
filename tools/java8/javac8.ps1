<#
.SYNOPSIS
    Runs javac 8 (Eclipse Temurin 1.8.0_504) with passed arguments.
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CompilerArgs
)

$KnownPaths = @(
    $env:JAVA8_HOME,
    "C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot",
    "C:\Program Files\Java\jdk1.8.0_*"
)

$Javac8Bin = $null
foreach ($p in $KnownPaths) {
    if (-not [string]::IsNullOrEmpty($p)) {
        $resolved = Resolve-Path $p -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path (Join-Path $resolved.Path "bin\javac.exe"))) {
            $Javac8Bin = Join-Path $resolved.Path "bin\javac.exe"
            break
        }
    }
}

if (-not $Javac8Bin) {
    Write-Error "[Hermes] javac 8 executable not found. Set JAVA8_HOME."
    exit 1
}

& $Javac8Bin @CompilerArgs
