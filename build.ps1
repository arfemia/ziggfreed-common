# Build + install the Ziggfreed Common plugin jar in one shot.
#
# Self-contained (mirrors the kweebec-nightmare build.ps1): gradlew build, pin the
# exact runtime jar by version (NOT the -sources/-javadoc siblings), and copy it
# into the Hytale Mods folder when one is known.
#
#   .\build.ps1                    # build + install into the target below
#   .\build.ps1 -Install:$false    # build only, copy nothing
#   .\build.ps1 -ModsDir <path>    # install target override
#
# The install target (R205, R206): an explicit -ModsDir always wins. Otherwise, in a workspace, its
# tools\mods-dir.ps1 (found by walking up, like the lane) picks the Mods folder of the client
# family.properties' patchline plays, or HYTALE_MODS_DIR when no Hytale install is found. Cloned
# alone, it is HYTALE_MODS_DIR. A target folder that does not exist fails the install.
param(
    [bool]$Install = $true,
    [string]$ModsDir = ''
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host "`n=== Building ZiggfreedCommon (gradle build) ===" -ForegroundColor Cyan
# Gradle runs through the workspace's lane when a folder above this repo holds tools\lane\lane.ps1 (R139):
# the lane waits for a free machine and keeps one build per tree. Cloned alone, it runs gradlew here.
function Find-Lane([string]$From) {
    # Walks up from $From's parent to the workspace root (main's, or a tree's, which holds its own tools\).
    $dir = Split-Path -Parent $From
    while ($dir) {
        $candidate = Join-Path $dir 'tools\lane\lane.ps1'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        $dir = Split-Path -Parent $dir
    }
    return $null
}
# The install target (see the header), from the first tools\mods-dir.ps1 above this repo, looked for up to the
# family root (the folder holding family.properties, whose patchline that resolver reads); with none, -ModsDir,
# else HYTALE_MODS_DIR. Returns Path ($null when there is none) and Display (the client or source, and the folder).
function Resolve-InstallTarget([string]$From, [string]$Explicit) {
    $dir = Split-Path -Parent $From
    while ($dir) {
        $resolver = Join-Path (Join-Path $dir 'tools') 'mods-dir.ps1'
        if (Test-Path -LiteralPath $resolver -PathType Leaf) { . $resolver; return (Resolve-ModsDir -ModsDir $Explicit) }
        if (Test-Path -LiteralPath (Join-Path $dir 'family.properties') -PathType Leaf) { break }
        $dir = Split-Path -Parent $dir
    }
    if ($Explicit) { return [pscustomobject]@{ Path = $Explicit; Display = "$Explicit (-ModsDir)" } }
    if ($env:HYTALE_MODS_DIR) { return [pscustomobject]@{ Path = $env:HYTALE_MODS_DIR; Display = "$($env:HYTALE_MODS_DIR) (HYTALE_MODS_DIR, no workspace resolver above this repo)" } }
    return [pscustomobject]@{ Path = $null; Display = 'none' }
}
$lane = Find-Lane $root
if ($lane) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $lane build -Dir $root -Tasks 'build'
} else {
    & (Join-Path $root 'gradlew.bat') build
}
if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }

# Pin the runtime jar by gradle.properties version. The -Filter glob MUST NOT be
# 'ZiggfreedCommon-*.jar' - that also matches the -sources.jar / -javadoc.jar siblings.
$modName = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^name=(.+)$').Matches[0].Groups[1].Value.Trim()
$modVersion = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
$jarName = "$modName-$modVersion.jar"
$jarFile = Get-ChildItem -Path (Join-Path $root 'build\libs') -Filter $jarName -File | Select-Object -First 1
if (-not $jarFile) { throw "No $jarName found in build\libs after build" }
Write-Host "Built $($jarFile.Name)"

if (-not $Install) { return }
$explicitModsDir = if ($PSBoundParameters.ContainsKey('ModsDir')) { $ModsDir } else { '' }
$target = Resolve-InstallTarget $root $explicitModsDir
$ModsDir = $target.Path
if (-not $ModsDir) {
    Write-Host "No Mods folder found - pass -ModsDir <path>, or set `$env:HYTALE_MODS_DIR (used only when no Hytale install is found), to install." -ForegroundColor Yellow
    return
}
Write-Host "Install target: $($target.Display)"
if (-not (Test-Path $ModsDir)) { throw "Mods folder does not exist: $ModsDir" }

# Remove ONLY runtime plugin jars (never the -sources/-javadoc siblings) so an old
# runtime jar is always cleared and a stray secondary can't survive as the loaded plugin.
Get-ChildItem -Path $ModsDir -Filter "$modName-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item $jarFile.FullName (Join-Path $ModsDir $jarFile.Name) -Force
Write-Host "Installed jar to $(Join-Path $ModsDir $jarFile.Name)" -ForegroundColor Green
