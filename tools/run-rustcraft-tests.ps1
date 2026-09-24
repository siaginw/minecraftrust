<#
.SYNOPSIS
    Runs a bounded RustCraft test lane and records its inventory and results.
.DESCRIPTION
    Requires Python 3.9+ and the lane's documented toolchain. No dependencies
    are installed. Java lanes require JAVA8_HOME or JAVA_HOME pointing at 8u504.
    See tools/testing/README.md for scope, cache identity and exit codes.
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('public', 'java-jni', 'fixture', 'forge', 'modpack', 'benchmark')]
    [string]$Lane = 'public',
    [string]$JavaHome,
    [string]$ForgeClasspathManifest,
    [string]$ModpackArtifactManifest,
    [switch]$Inventory
)

$ErrorActionPreference = 'Stop'
$RunnerArgs = @('-B', (Join-Path $PSScriptRoot 'testing/run_tests.py'), $Lane)
if ($JavaHome) { $RunnerArgs += @('--java-home', $JavaHome) }
if ($ForgeClasspathManifest) { $RunnerArgs += @('--forge-classpath-manifest', $ForgeClasspathManifest) }
if ($ModpackArtifactManifest) { $RunnerArgs += @('--modpack-artifact-manifest', $ModpackArtifactManifest) }
if ($Inventory) { $RunnerArgs += '--inventory' }
& python @RunnerArgs
exit $LASTEXITCODE
