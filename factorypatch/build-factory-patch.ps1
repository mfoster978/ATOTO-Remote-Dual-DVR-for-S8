$ErrorActionPreference = 'Stop'

$PatchRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $PatchRoot
$FactoryApk = Join-Path $ProjectRoot 'factory-hn7-dvr.apk'
$Apktool = Join-Path $ProjectRoot '_tools\apktool\apktool_3.0.3.jar'
$PrivateRoot = Join-Path $ProjectRoot '.private'
$DecodeRoot = Join-Path $PrivateRoot 'factory-dvr-enhanced-build'
$KeyRoot = Join-Path $PrivateRoot 'aosp-platform-key'
$PlatformKey = Join-Path $KeyRoot 'platform.pk8'
$PlatformCert = Join-Path $KeyRoot 'platform.x509.pem'
$Out = Join-Path $PatchRoot 'out'
$Classes = Join-Path $Out 'classes'
$Dex = Join-Path $Out 'dex'
$ClassesJar = Join-Path $Out 'camera-link-enhancements.jar'
$Unsigned = Join-Path $Out 'enhanced-stock-dvr-unsigned.apk'
$WithDex = Join-Path $Out 'enhanced-stock-dvr-with-dex.apk'
$Aligned = Join-Path $Out 'enhanced-stock-dvr-aligned.apk'
$Signed = Join-Path $Out 'ATOTO-S8-Enhanced-Stock-DVR-v2.0.0.apk'
$Published = Join-Path $ProjectRoot 'ATOTO-S8-Enhanced-Stock-DVR-v2.0.0.apk'
$ZxingJar = Join-Path $ProjectRoot 'app\lib\zxing-core-3.5.3.jar'
$ExpectedCertificate = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'

$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$BuildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object Name -Match '^\d+\.\d+\.\d+$' |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$AndroidJar = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platforms') -Directory |
    Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Select-Object -First 1
$AndroidBootClasses = Join-Path $ProjectRoot '_tools\android-35-classes'
$JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } elseif (Test-Path 'C:\Program Files\Android\Android Studio\jbr') { 'C:\Program Files\Android\Android Studio\jbr' } else { 'C:\Program Files\Android\Android Studio1\jbr' }
$env:JAVA_HOME = $JavaRoot
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($required in @($FactoryApk, $Apktool, $PlatformKey, $PlatformCert, $AndroidJar, $AndroidBootClasses, $ZxingJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing required file: $required" }
}

$ResolvedProject = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
foreach ($generated in @($Out, $DecodeRoot)) {
    $resolved = [IO.Path]::GetFullPath($generated)
    if (-not $resolved.StartsWith($ResolvedProject + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the project: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $Out, $Classes, $Dex | Out-Null

$FactoryCertificate = (& (Join-Path $BuildTools 'apksigner.bat') verify --print-certs $FactoryApk) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $FactoryCertificate -notmatch "SHA-256 digest: $ExpectedCertificate") {
    throw 'Factory APK is not signed with the expected S8 platform certificate.'
}

java -jar $Apktool d -f -o $DecodeRoot $FactoryApk
if ($LASTEXITCODE -ne 0) { throw 'Apktool decode failed.' }

$ApktoolYaml = Join-Path $DecodeRoot 'apktool.yml'
$Yaml = Get-Content -Raw -LiteralPath $ApktoolYaml
$Yaml = $Yaml -replace '(?m)^  versionCode:.*$', '  versionCode: 210000'
$Yaml = $Yaml -replace '(?m)^  versionName:.*$', '  versionName: 2.0.0-camera-link'
[IO.File]::WriteAllText($ApktoolYaml, $Yaml, $Utf8NoBom)

$Manifest = Join-Path $DecodeRoot 'AndroidManifest.xml'
$ManifestText = Get-Content -Raw -LiteralPath $Manifest
$Permissions = @'
    <uses-permission android:name="android.permission.CAMERA"/>
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
    <uses-permission android:name="android.permission.USE_EXACT_ALARM"/>
    <uses-feature android:name="android.hardware.camera.any" android:required="false"/>
    <queries>
        <package android:name="com.tailscale.ipn"/>
    </queries>
'@
if ($ManifestText -notmatch 'android.permission.CAMERA') {
    $ManifestText = $ManifestText -replace '(\s*<permission android:name="com\.hcn\.dvr\.DYNAMIC)', ([Environment]::NewLine + $Permissions + '    <permission android:name="com.hcn.dvr.DYNAMIC')
}
$ManifestText = $ManifestText -replace '<application ', '<application android:allowBackup="false" android:requestLegacyExternalStorage="true" android:usesCleartextTraffic="true" '
$Components = @'
        <activity android:name="com.codex.hn7camerastream.MainActivity" android:exported="false" android:theme="@android:style/Theme.Material.Light.NoActionBar"/>
        <activity android:name="com.codex.hn7camerastream.QrScannerActivity" android:exported="false" android:screenOrientation="portrait" android:theme="@android:style/Theme.Material.Light.NoActionBar"/>
        <activity android:name="com.codex.hn7camerastream.BootBootstrapActivity" android:excludeFromRecents="true" android:exported="false" android:finishOnTaskLaunch="true" android:noHistory="true" android:showWhenLocked="true" android:theme="@android:style/Theme.Translucent.NoTitleBar"/>
        <service android:name="com.codex.hn7camerastream.CameraStreamService" android:enabled="true" android:exported="false" android:foregroundServiceType="camera|microphone|location"/>
        <receiver android:name="com.codex.hn7camerastream.BootReceiver" android:enabled="true" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/>
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
                <action android:name="android.intent.action.USER_UNLOCKED"/>
                <action android:name="android.mcu.device.action.ACC"/>
            </intent-filter>
        </receiver>
        <provider android:name="com.codex.hn7camerastream.BundledApkProvider" android:authorities="com.hcn.dvr.apks" android:exported="true" android:grantUriPermissions="true"/>
'@
if ($ManifestText -notmatch 'com.codex.hn7camerastream.MainActivity') {
    $ManifestText = $ManifestText -replace '\s*</application>', ([Environment]::NewLine + $Components + '    </application>')
}
[IO.File]::WriteAllText($Manifest, $ManifestText, $Utf8NoBom)

$DecodedAssets = Join-Path $DecodeRoot 'assets'
New-Item -ItemType Directory -Force -Path $DecodedAssets | Out-Null
Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\assets') -File |
    Where-Object Name -NE 'atoto-s8-hn7-factory-dvr-integration.apk' |
    Copy-Item -Destination $DecodedAssets -Force

$CameraSmali = Join-Path $DecodeRoot 'smali\com\hcn\dvr\manager\HCamera1Manager.smali'
$Smali = Get-Content -Raw -LiteralPath $CameraSmali
$Needle = '    invoke-static {p1, v4, v3, v0, v1}, Lcom/hcn/dvr/utils/WatermarkUtils;->blendWatermark([BIIII)V'
$CameraPatch = @"
$Needle

    # Camera Link read-only frame tap. Work is rate-limited and asynchronous.
    iget v2, p0, Lcom/hcn/dvr/manager/HCamera1Manager;->cameraId:I

    iget-object v5, p0, Lcom/hcn/dvr/manager/HCamera1Manager;->context:Landroid/content/Context;

    invoke-static {p1, v4, v3, v2, v5}, Lcom/hcn/dvr/remote/RemoteFrameTap;->offerNv21([BIIILandroid/content/Context;)V
"@
if (-not $Smali.Contains($Needle)) { throw 'Factory camera callback patch point was not found.' }
$Smali = $Smali.Replace($Needle, $CameraPatch)
[IO.File]::WriteAllText($CameraSmali, $Smali, $Utf8NoBom)

$ActivitySmaliPath = Join-Path $DecodeRoot 'smali\com\hcn\dvr\HDvrActivity.smali'
$ActivitySmali = Get-Content -Raw -LiteralPath $ActivitySmaliPath
$ActivityCreateNeedle = '    invoke-direct {p0}, Lcom/hcn/dvr/HDvrActivity;->initView()V'
$ActivityCreatePatch = $ActivityCreateNeedle + [Environment]::NewLine + [Environment]::NewLine +
    '    # Additive Camera Link overlay; the factory camera view remains intact.' + [Environment]::NewLine +
    '    invoke-static {p0}, Lcom/codex/hn7camerastream/StockDvrUiBridge;->attach(Landroid/app/Activity;)V'
if (-not $ActivitySmali.Contains($ActivityCreateNeedle)) { throw 'HDvrActivity initView patch point was not found.' }
$ActivitySmali = $ActivitySmali.Replace($ActivityCreateNeedle, $ActivityCreatePatch)
$DestroyNeedle = ".method protected onDestroy()V`r`n    .locals 2"
if (-not $ActivitySmali.Contains($DestroyNeedle)) { $DestroyNeedle = ".method protected onDestroy()V`n    .locals 2" }
if (-not $ActivitySmali.Contains($DestroyNeedle)) { throw 'HDvrActivity onDestroy patch point was not found.' }
$DestroyPatch = $DestroyNeedle + [Environment]::NewLine + [Environment]::NewLine +
    '    invoke-static {p0}, Lcom/codex/hn7camerastream/StockDvrUiBridge;->detach(Landroid/app/Activity;)V'
$ActivitySmali = $ActivitySmali.Replace($DestroyNeedle, $DestroyPatch)
$ResumeNeedle = '    invoke-virtual {v0, v1}, Lcom/hcn/dvr/view/WatermarkView;->setWatermarkGPSVisible(Z)V' + [Environment]::NewLine + [Environment]::NewLine + '    return-void' + [Environment]::NewLine + '.end method' + [Environment]::NewLine + [Environment]::NewLine + '.method protected onStop()V'
if (-not $ActivitySmali.Contains($ResumeNeedle)) { throw 'HDvrActivity onResume owner-lock patch point was not found.' }
$ResumePatch = '    invoke-virtual {v0, v1}, Lcom/hcn/dvr/view/WatermarkView;->setWatermarkGPSVisible(Z)V' + [Environment]::NewLine + [Environment]::NewLine +
    '    invoke-static {p0}, Lcom/codex/hn7camerastream/StockDvrUiBridge;->onFactoryDvrResume(Landroid/app/Activity;)V' + [Environment]::NewLine + [Environment]::NewLine +
    '    return-void' + [Environment]::NewLine + '.end method' + [Environment]::NewLine + [Environment]::NewLine + '.method protected onStop()V'
$ActivitySmali = $ActivitySmali.Replace($ResumeNeedle, $ResumePatch)
$StopNeedle = '    invoke-super {p0}, Landroid/app/Activity;->onStop()V'
if (-not $ActivitySmali.Contains($StopNeedle)) { throw 'HDvrActivity onStop owner-lock patch point was not found.' }
$StopPatch = $StopNeedle + [Environment]::NewLine + [Environment]::NewLine +
    '    invoke-static {p0}, Lcom/codex/hn7camerastream/StockDvrUiBridge;->lockOwnerControls(Landroid/app/Activity;)V'
$ActivitySmali = $ActivitySmali.Replace($StopNeedle, $StopPatch)
[IO.File]::WriteAllText($ActivitySmaliPath, $ActivitySmali, $Utf8NoBom)

$SettingsSmaliPath = Join-Path $DecodeRoot 'smali\com\hcn\dvr\SettingsFragment.smali'
$SettingsSmali = Get-Content -Raw -LiteralPath $SettingsSmaliPath
$SettingsNeedle = '    invoke-direct {p0, p2}, Lcom/hcn/dvr/SettingsFragment;->updateUiMode(I)V' + [Environment]::NewLine + [Environment]::NewLine + '    return-object p1'
if (-not $SettingsSmali.Contains($SettingsNeedle)) { throw 'SettingsFragment return patch point was not found.' }
$SettingsPatch = '    invoke-direct {p0, p2}, Lcom/hcn/dvr/SettingsFragment;->updateUiMode(I)V' + [Environment]::NewLine + [Environment]::NewLine +
    '    # Append Camera Link rows after all original settings and listeners exist.' + [Environment]::NewLine +
    '    invoke-static {p0, p1}, Lcom/codex/hn7camerastream/StockDvrSettingsBridge;->attach(Ljava/lang/Object;Landroid/view/View;)V' + [Environment]::NewLine + [Environment]::NewLine +
    '    return-object p1'
$SettingsSmali = $SettingsSmali.Replace($SettingsNeedle, $SettingsPatch)
[IO.File]::WriteAllText($SettingsSmaliPath, $SettingsSmali, $Utf8NoBom)

$FolderActivitySmaliPath = Join-Path $DecodeRoot 'smali\com\hcn\dvr\FolderPlaybackActivity.smali'
$FolderActivitySmali = Get-Content -Raw -LiteralPath $FolderActivitySmaliPath
$FolderNeedle = '    invoke-virtual {p0, p1}, Lcom/hcn/dvr/FolderPlaybackActivity;->setContentView(I)V'
$FolderPatch = $FolderNeedle + [Environment]::NewLine + [Environment]::NewLine +
    '    # UI-only owner gate. Native fragments continue initializing behind it.' + [Environment]::NewLine +
    '    invoke-static {p0}, Lcom/codex/hn7camerastream/StockDvrAccessGuard;->protect(Landroid/app/Activity;)V'
if (-not $FolderActivitySmali.Contains($FolderNeedle)) { throw 'FolderPlaybackActivity owner-lock patch point was not found.' }
$FolderActivitySmali = $FolderActivitySmali.Replace($FolderNeedle, $FolderPatch)
[IO.File]::WriteAllText($FolderActivitySmaliPath, $FolderActivitySmali, $Utf8NoBom)

# Keep ATOTO's native recordings UI and player, but merge completed reverse
# clips from Camera Link into the factory Daily/All library.
$FolderSmaliPath = Join-Path $DecodeRoot 'smali\com\hcn\dvr\FolderFragment.smali'
$FolderSmali = Get-Content -Raw -LiteralPath $FolderSmaliPath
$ListMethodMatch = [regex]::Match($FolderSmali,
    '(?s)\.method private listFiles\(Ljava/lang/String;\)Ljava/util/List;.*?\.end method')
if (-not $ListMethodMatch.Success) { throw 'FolderFragment listFiles method was not found.' }
$ListMethod = $ListMethodMatch.Value.Replace('    .locals 3', '    .locals 4')
$AnnotationEnd = '    .end annotation' + [Environment]::NewLine
if (-not $ListMethod.Contains($AnnotationEnd)) { throw 'FolderFragment listFiles annotation end was not found.' }
$ListMethod = $ListMethod.Replace($AnnotationEnd,
    $AnnotationEnd + [Environment]::NewLine + '    move-object v3, p1' + [Environment]::NewLine)
$ListReturn = '    return-object p1' + [Environment]::NewLine + '.end method'
$ListReturnPatch = @"
    invoke-virtual {p0}, Lcom/hcn/dvr/FolderFragment;->requireContext()Landroid/content/Context;

    move-result-object v0

    invoke-static {v0, p1, v3}, Lcom/codex/hn7camerastream/StockDvrLibraryBridge;->mergeReverseVideos(Landroid/content/Context;Ljava/util/List;Ljava/lang/String;)Ljava/util/List;

    move-result-object p1

    return-object p1
.end method
"@
if (-not $ListMethod.Contains($ListReturn)) { throw 'FolderFragment listFiles return was not found.' }
$ListMethod = $ListMethod.Replace($ListReturn, $ListReturnPatch.TrimEnd())
$FolderSmali = $FolderSmali.Remove($ListMethodMatch.Index, $ListMethodMatch.Length)
$FolderSmali = $FolderSmali.Insert($ListMethodMatch.Index, $ListMethod)
[IO.File]::WriteAllText($FolderSmaliPath, $FolderSmali, $Utf8NoBom)

# Give reverse clips an R badge in the existing factory grid. The stock F/C
# filters remain unchanged; reverse clips are shown in the normal All view.
$VideoHelperSmaliPath = Join-Path $DecodeRoot 'smali\com\hcn\dvr\utils\VideoHelper.smali'
$VideoHelperSmali = Get-Content -Raw -LiteralPath $VideoHelperSmaliPath
$TypeMethodMatch = [regex]::Match($VideoHelperSmali,
    '(?s)\.method public static isType\(Ljava/io/File;\)Ljava/lang/String;.*?\.end method')
if (-not $TypeMethodMatch.Success) { throw 'VideoHelper isType method was not found.' }
$TypeMethod = @'
.method public static isType(Ljava/io/File;)Ljava/lang/String;
    .locals 2

    invoke-virtual {p0}, Ljava/io/File;->getName()Ljava/lang/String;
    move-result-object p0

    const-string v0, "F-"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :check_c
    const-string p0, "F"
    return-object p0

    :check_c
    const-string v0, "C-"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :check_r
    const-string p0, "C"
    return-object p0

    :check_r
    const-string v0, "R-"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result p0
    if-eqz p0, :unknown
    const-string p0, "R"
    return-object p0

    :unknown
    const-string p0, " "
    return-object p0
.end method
'@
$VideoHelperSmali = $VideoHelperSmali.Remove($TypeMethodMatch.Index, $TypeMethodMatch.Length)
$VideoHelperSmali = $VideoHelperSmali.Insert($TypeMethodMatch.Index, $TypeMethod.TrimEnd())
[IO.File]::WriteAllText($VideoHelperSmaliPath, $VideoHelperSmali, $Utf8NoBom)

$Sources = @(
    Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src') -Recurse -Filter *.java | ForEach-Object FullName
    Get-ChildItem -LiteralPath (Join-Path $PatchRoot 'src') -Recurse -Filter *.java | ForEach-Object FullName
)
& (Join-Path $JavaRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -classpath $ZxingJar -d $Classes $Sources
if ($LASTEXITCODE -ne 0) { throw 'Enhanced stock DVR javac failed.' }
& (Join-Path $JavaRoot 'bin\jar.exe') cf $ClassesJar -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'Enhancement jar failed.' }
& (Join-Path $BuildTools 'd8.bat') --lib $AndroidJar --min-api 26 --output $Dex $ClassesJar $ZxingJar
if ($LASTEXITCODE -ne 0) { throw 'Enhancement D8 failed.' }
$GeneratedDex = Join-Path $Dex 'classes.dex'
$SecondaryDex = Join-Path $Dex 'classes2.dex'
Move-Item -LiteralPath $GeneratedDex -Destination $SecondaryDex

java -jar $Apktool b -o $Unsigned $DecodeRoot
if ($LASTEXITCODE -ne 0) { throw 'Apktool build failed.' }
Copy-Item -LiteralPath $Unsigned -Destination $WithDex
& (Join-Path $JavaRoot 'bin\jar.exe') uf $WithDex -C $Dex classes2.dex
if ($LASTEXITCODE -ne 0) { throw 'Adding Camera Link classes2.dex failed.' }
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $WithDex $Aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
& (Join-Path $BuildTools 'apksigner.bat') sign --key $PlatformKey --cert $PlatformCert --out $Signed $Aligned
if ($LASTEXITCODE -ne 0) { throw 'Platform signing failed.' }

$SignedCertificate = (& (Join-Path $BuildTools 'apksigner.bat') verify --verbose --print-certs $Signed) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $SignedCertificate -notmatch "SHA-256 digest: $ExpectedCertificate") {
    throw 'Enhanced stock DVR signature verification failed.'
}
$Badging = (& (Join-Path $BuildTools 'aapt2.exe') dump badging $Signed) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $Badging -notmatch "package: name='com.hcn.dvr' versionCode='210000' versionName='2.0.0-camera-link'") {
    throw 'Enhanced APK did not preserve the package or apply the expected high version.'
}
$ArchiveEntries = (& (Join-Path $JavaRoot 'bin\jar.exe') tf $Signed) -join [Environment]::NewLine
foreach ($requiredEntry in @('classes.dex', 'classes2.dex', 'assets/tailscale-android-universal-1.104.1.apk')) {
    if ($ArchiveEntries -notmatch [regex]::Escape($requiredEntry)) { throw "Enhanced APK is missing $requiredEntry" }
}
Copy-Item -LiteralPath $Signed -Destination $Published -Force
$SignedCertificate
$Badging | Select-Object -First 12
Get-FileHash -Algorithm SHA256 -LiteralPath $Signed
Write-Output $Published
