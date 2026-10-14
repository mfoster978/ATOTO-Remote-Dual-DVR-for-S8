$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OriginalRoot = Join-Path $ProjectRoot '.private\factory-dvr-apktool\smali\com\hcn\dvr'
$PatchedRoot = Join-Path $ProjectRoot '.private\factory-dvr-enhanced-build\smali\com\hcn\dvr'

function Get-Method([string]$Source, [string]$Signature) {
    $pattern = '(?ms)^\.method public ' + [regex]::Escape($Signature) + '\r?\n.*?^\.end method'
    $matches = [regex]::Matches($Source, $pattern)
    if ($matches.Count -ne 1) { throw "Expected one factory method: $Signature" }
    return $matches[0].Value
}
function Normalize-Instructions([string]$Source) {
    return (($Source -split '\r?\n' | Where-Object { $_.Trim() -and $_ -notmatch '^\s*#' }) -join "`n")
}

$OriginalHelper = Get-Content -Raw -LiteralPath (Join-Path $OriginalRoot 'utils\DataHelper.smali')
$PatchedHelper = Get-Content -Raw -LiteralPath (Join-Path $PatchedRoot 'utils\DataHelper.smali')
$Getter = Get-Method $PatchedHelper 'getSentryModeEnable()Z'
if ($Getter -notmatch 'const/4 v0, 0x0\s+return v0' -or $Getter -match 'invoke-|if-|goto') {
    throw 'Parking getter does not unconditionally return OFF.'
}
$Setter = Get-Method $PatchedHelper 'setSentryModeEnable(Z)V'
if ($Setter -notmatch 'const/4 p1, 0x0\s+invoke-direct \{p0, v0, p1\}.*setIntValue') {
    throw 'Parking setter does not force the saved value to OFF.'
}
Write-Output 'PASS native queries reject a restored enabled value and setters cannot enable parking'

foreach ($signature in @('getSentryModeEnable()Z', 'setSentryModeEnable(Z)V')) {
    $OriginalHelper = $OriginalHelper.Replace((Get-Method $OriginalHelper $signature), '')
    $PatchedHelper = $PatchedHelper.Replace((Get-Method $PatchedHelper $signature), '')
}
if ((Normalize-Instructions $OriginalHelper) -cne (Normalize-Instructions $PatchedHelper)) {
    throw 'An unrelated native setting or recorder method changed.'
}
foreach ($name in @('manager\SentryModeManager.smali', 'manager\SentryModeManager$$ExternalSyntheticLambda0.smali')) {
    if ((Get-FileHash -LiteralPath (Join-Path $OriginalRoot $name)).Hash -ne
            (Get-FileHash -LiteralPath (Join-Path $PatchedRoot $name)).Hash) {
        throw "Native sentry implementation changed: $name"
    }
}
Write-Output 'PASS other native settings, thresholds, duration values, and sentry implementation are preserved'

$Settings = Get-Content -Raw -LiteralPath (Join-Path $PatchedRoot 'SettingsFragment.smali')
$Mask = [regex]::Match($Settings, '(?ms)^\.method private updateParkingMask\(\)V\r?\n.*?^\.end method').Value
if ($Mask -notmatch 'StockDvrParkingLock;->apply\(Ljava/lang/Object;\)V\s+return-void') {
    throw 'Native UI refresh does not reapply the parking lock.'
}
$Bootstrap = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'app\src\com\codex\hn7camerastream\EnhancedDvrBootstrap.java')
if ($Bootstrap -notmatch 'StockDvrParkingLock.disableSavedSetting\(application\)') {
    throw 'Upgrade startup does not clear a previously enabled parking flag.'
}
Write-Output 'PASS the parking lock survives native UI refresh and clears previously enabled settings at startup'
