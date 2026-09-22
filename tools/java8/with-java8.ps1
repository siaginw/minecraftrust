<#
.SYNOPSIS
    Runs a script block or command with JAVA_HOME pointing to Java 8.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Command,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CommandArgs
)

$KnownPaths = @(
    $env:JAVA8_HOME,
    "C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot",
    "C:\Program Files\Java\jdk1.8.0_*"
)

$Java8Home = $null
foreach ($p in $KnownPaths) {
    if (-not [string]::IsNullOrEmpty($p)) {
        $resolved = Resolve-Path $p -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path (Join-Path $resolved.Path "bin\java.exe"))) {
            $Java8Home = $resolved.Path
            break
        }
    }
}

if (-not $Java8Home) {
    Write-Error "[Hermes] Java 8 JDK home not found. Set JAVA8_HOME."
    exit 1
}

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    $env:JAVA_HOME = $Java8Home
    $env:PATH = "$Java8Home\bin;$oldPath"
    & $Command @CommandArgs
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
