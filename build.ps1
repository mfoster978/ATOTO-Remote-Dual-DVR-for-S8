$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$BuildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$LocalTools = Join-Path $ProjectRoot '_tools'
$AndroidJar = Join-Path $LocalTools 'android-35.jar'
$SdkAndroidJar = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platforms') -Directory | Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending | ForEach-Object { Join-Path $_.FullName 'android.jar' } | Select-Object -First 1
$JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } elseif (Test-Path 'C:\Program Files\Android\Android Studio\jbr') { 'C:\Program Files\Android\Android Studio\jbr' } else { 'C:\Program Files\Android\Android Studio1\jbr' }
$env:JAVA_HOME = $JavaRoot
$Out = Join-Path $ProjectRoot 'out'
$Classes = Join-Path $Out 'classes'
$Dex = Join-Path $Out 'dex'
$CompiledResources = Join-Path $Out 'resources.zip'
$Unsigned = Join-Path $Out 'HN7CameraStream-unsigned.apk'
$Aligned = Join-Path $Out 'HN7CameraStream-aligned.apk'
$Signed = Join-Path $Out 'HN7CameraStream.apk'
$SigningProperties = Join-Path $ProjectRoot 'keystore.properties'
$KeyAlias = 'camera-link-dev'
$StorePassword = 'camera-link-development-only'
$KeyPassword = $StorePassword
$Keystore = Join-Path $ProjectRoot 'camera-link-development.keystore'
if (Test-Path -LiteralPath $SigningProperties) {
    $Signing = Get-Content -Raw -LiteralPath $SigningProperties | ConvertFrom-StringData
    if (-not $Signing.keystorePath -or -not $Signing.keyAlias -or -not $Signing.storePassword -or -not $Signing.keyPassword) {
        throw 'keystore.properties must define keystorePath, keyAlias, storePassword, and keyPassword.'
    }
    $Keystore = if ([IO.Path]::IsPathRooted($Signing.keystorePath)) { $Signing.keystorePath } else { Join-Path $ProjectRoot $Signing.keystorePath }
    $KeyAlias = $Signing.keyAlias
    $StorePassword = $Signing.storePassword
    $KeyPassword = $Signing.keyPassword
}
$BundledTailscale = Join-Path $ProjectRoot 'app\assets\tailscale-android-universal-1.104.1.apk'
$TailscaleDownloadUrl = 'https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.1.apk'
$ExpectedTailscaleSha256 = 'B3A1A81B52D1437769E5570C89A95F5824518A6B376D10C39EB64AF213A625B6'
$ZxingJar = Join-Path $ProjectRoot 'app\lib\zxing-core-3.5.3.jar'
$ExpectedZxingSha256 = '8D8064C1636FDAEF7189DD9055C7D59950A8940A12F2293956446EC3C109FD82'

if (-not (Test-Path -LiteralPath $BundledTailscale)) {
    Write-Output 'Downloading the pinned official Tailscale Android package...'
    Invoke-WebRequest -Uri $TailscaleDownloadUrl -OutFile $BundledTailscale
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $BundledTailscale).Hash -ne $ExpectedTailscaleSha256) { throw 'Bundled Tailscale APK checksum does not match the official package.' }
if (-not (Test-Path -LiteralPath $ZxingJar)) { throw 'ZXing core library is missing.' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $ZxingJar).Hash -ne $ExpectedZxingSha256) { throw 'ZXing core checksum does not match the pinned package.' }

New-Item -ItemType Directory -Force -Path $LocalTools | Out-Null
if (-not (Test-Path -LiteralPath $AndroidJar)) {
    Copy-Item -LiteralPath $SdkAndroidJar -Destination $AndroidJar
}

Remove-Item -LiteralPath $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Classes, $Dex | Out-Null

if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JavaRoot 'bin\keytool.exe') -genkeypair -keystore $Keystore -storepass $StorePassword -keypass $KeyPassword -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Camera Link Development, OU=Open Source Build, O=Camera Link, C=US'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

# Build the small radio-only compatibility bridge first and bundle its signed
# APK inside Camera Link. The bridge targets Android's legacy settings API so
# ATOTO's custom camera_type key can be updated; the main phone/radio APK keeps
# its modern target SDK.
$BridgeClasses = Join-Path $Out 'muxbridge-classes'
$BridgeDex = Join-Path $Out 'muxbridge-dex'
$BridgeJar = Join-Path $Out 'muxbridge-classes.jar'
$BridgeUnsigned = Join-Path $Out 'ATOTO-Mux-Bridge-unsigned.apk'
$BridgeAligned = Join-Path $Out 'ATOTO-Mux-Bridge-aligned.apk'
$BridgeSigned = Join-Path $Out 'ATOTO-Mux-Bridge.apk'
$BridgeAsset = Join-Path $ProjectRoot 'app\assets\atoto-mux-bridge.apk'
New-Item -ItemType Directory -Force -Path $BridgeClasses, $BridgeDex | Out-Null
$BridgeSources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'muxbridge\src') -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -d $BridgeClasses $BridgeSources
if ($LASTEXITCODE -ne 0) { throw 'mux bridge javac failed' }
& (Join-Path $JavaRoot 'bin\jar.exe') cf $BridgeJar -C $BridgeClasses .
if ($LASTEXITCODE -ne 0) { throw 'mux bridge jar failed' }
& (Join-Path $BuildTools 'd8.bat') --lib $AndroidJar --min-api 21 --output $BridgeDex $BridgeJar
if ($LASTEXITCODE -ne 0) { throw 'mux bridge d8 failed' }
& (Join-Path $BuildTools 'aapt2.exe') link -o $BridgeUnsigned -I $AndroidJar --manifest (Join-Path $ProjectRoot 'muxbridge\AndroidManifest.xml') --min-sdk-version 21 --target-sdk-version 22 --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw 'mux bridge packaging failed' }
& (Join-Path $JavaRoot 'bin\jar.exe') uf $BridgeUnsigned -C $BridgeDex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'adding mux bridge classes.dex failed' }
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $BridgeUnsigned $BridgeAligned
if ($LASTEXITCODE -ne 0) { throw 'mux bridge zipalign failed' }
& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass "pass:$StorePassword" --key-pass "pass:$KeyPassword" --out $BridgeSigned $BridgeAligned
if ($LASTEXITCODE -ne 0) { throw 'mux bridge signing failed' }
Copy-Item -LiteralPath $BridgeSigned -Destination $BridgeAsset -Force

$Sources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src') -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $JavaRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -bootclasspath $AndroidJar -classpath $ZxingJar -d $Classes $Sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

& (Join-Path $JavaRoot 'bin\jar.exe') cf (Join-Path $Out 'classes.jar') -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

$env:JAVA_HOME = $JavaRoot
& (Join-Path $BuildTools 'd8.bat') --lib $AndroidJar --min-api 26 --output $Dex (Join-Path $Out 'classes.jar') $ZxingJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

& (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $ProjectRoot 'app\res') -o $CompiledResources
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource compile failed' }

& (Join-Path $BuildTools 'aapt2.exe') link -o $Unsigned -I $AndroidJar --manifest (Join-Path $ProjectRoot 'app\AndroidManifest.xml') -R $CompiledResources -A (Join-Path $ProjectRoot 'app\assets') --min-sdk-version 26 --target-sdk-version 33 --version-code 16 --version-name 1.6
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

& (Join-Path $JavaRoot 'bin\jar.exe') uf $Unsigned -C $Dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'adding classes.dex failed' }

& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $Unsigned $Aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JavaRoot 'bin\keytool.exe') -genkeypair -keystore $Keystore -storepass $StorePassword -keypass $KeyPassword -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Camera Link Development, OU=Open Source Build, O=Camera Link, C=US'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass "pass:$StorePassword" --key-pass "pass:$KeyPassword" --out $Signed $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $BuildTools 'apksigner.bat') verify --verbose --print-certs $Signed
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }

Write-Output $Signed
Copy-Item -LiteralPath $Signed -Destination (Join-Path $ProjectRoot 'ATOTO-Remote-Dual-DVR-for-S8-v1.6.apk') -Force
