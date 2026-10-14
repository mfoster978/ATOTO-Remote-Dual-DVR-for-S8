$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Output = Join-Path $ProjectRoot '.private\reverse-geometry-tests'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio1\jbr' }
& (Join-Path $JavaRoot 'bin\javac.exe') -d $Output (Join-Path $ProjectRoot 'app\src\com\codex\hn7camerastream\AtotoReverseGeometry.java') (Join-Path $PSScriptRoot 'AtotoReverseGeometryTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Reverse geometry tests did not compile.' }
& (Join-Path $JavaRoot 'bin\java.exe') -cp $Output com.codex.hn7camerastream.AtotoReverseGeometryTest
if ($LASTEXITCODE -ne 0) { throw 'Reverse geometry tests failed.' }
