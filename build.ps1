# Build + install the Ziggfreed Common plugin jar in one shot.
#
# Self-contained (mirrors the kweebec-nightmare build.ps1): gradlew build, pin the
# exact runtime jar by version (NOT the -sources/-javadoc siblings), and copy it
# into the Hytale Mods folder when one is known.
#
#   .\build.ps1                    # build + install (uses $env:HYTALE_MODS_DIR)
#   .\build.ps1 -Install:$false    # build only, copy nothing
#   .\build.ps1 -ModsDir <path>    # install target override
param(
    [bool]$Install = $true,
    [string]$ModsDir = $env:HYTALE_MODS_DIR
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host "`n=== Building ZiggfreedCommon (gradlew build) ===" -ForegroundColor Cyan
& (Join-Path $root 'gradlew.bat') build
if ($LASTEXITCODE -ne 0) { throw "gradlew build failed (exit $LASTEXITCODE)" }

# Pin the runtime jar by gradle.properties version. The -Filter glob MUST NOT be
# 'ZiggfreedCommon-*.jar' - that also matches the -sources.jar / -javadoc.jar siblings.
$modName = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^name=(.+)$').Matches[0].Groups[1].Value.Trim()
$modVersion = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
$jarName = "$modName-$modVersion.jar"
$jarFile = Get-ChildItem -Path (Join-Path $root 'build\libs') -Filter $jarName -File | Select-Object -First 1
if (-not $jarFile) { throw "No $jarName found in build\libs after build" }
Write-Host "Built $($jarFile.Name)"

if (-not $Install) { return }
if (-not $ModsDir) {
    Write-Host "No Mods folder set - pass -ModsDir <path> or set `$env:HYTALE_MODS_DIR to install." -ForegroundColor Yellow
    return
}
if (-not (Test-Path $ModsDir)) { throw "Mods folder does not exist: $ModsDir" }

# Remove ONLY runtime plugin jars (never the -sources/-javadoc siblings) so an old
# runtime jar is always cleared and a stray secondary can't survive as the loaded plugin.
Get-ChildItem -Path $ModsDir -Filter "$modName-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item $jarFile.FullName (Join-Path $ModsDir $jarFile.Name) -Force
Write-Host "Installed jar to $(Join-Path $ModsDir $jarFile.Name)" -ForegroundColor Green
