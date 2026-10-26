# Compila o lume (MODTV Launcher) sem Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Uso: ./launcher/build.ps1 -OutputDirectory build-local
param([string]$OutputDirectory = 'build')
$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Defina ANDROID_HOME ou ANDROID_SDK_ROOT' }
$bt   = "$sdk\build-tools\36.0.0"   # o d8 do 34.0.0 falha com classes do javac 21
$jar  = "$sdk\platforms\android-34\android.jar"
if (-not $env:JAVA_HOME) { throw 'Defina JAVA_HOME para o JDK' }
$jbr = Join-Path $env:JAVA_HOME 'bin'
$here = $PSScriptRoot
if ($OutputDirectory -notmatch '^build(?:-[a-zA-Z0-9-]+)?$') { throw 'Nome de saída inválido' }
$out  = Join-Path $here $OutputDirectory
$env:JAVA_HOME = Split-Path $jbr   # d8/apksigner (.bat) usam o JAVA_HOME

$buildRootChecked = [IO.Path]::GetFullPath($here)
$buildOutChecked = [IO.Path]::GetFullPath($out)
if ($buildOutChecked -ne [IO.Path]::Combine($buildRootChecked, $OutputDirectory) -or $buildOutChecked -eq $buildRootChecked) {
    throw 'Diretório de build fora do destino autorizado'
}
if (Test-Path -LiteralPath $buildOutChecked) {
    throw 'Saída existente preservada. Informe -OutputDirectory build-<nome-novo>.'
}
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" link --manifest "$here\AndroidManifest.xml" -I $jar `
    --min-sdk-version 31 --target-sdk-version 34 -o "$out\base.apk"
if ($LASTEXITCODE) { throw 'aapt2 falhou' }

$src = Get-ChildItem -Recurse "$here\src" -Filter *.java | ForEach-Object FullName
& "$jbr\javac.exe" --release 11 -encoding UTF-8 -classpath $jar -d "$out\classes" $src
if ($LASTEXITCODE) { throw 'javac falhou' }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --lib $jar --min-api 31 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw 'd8 falhou' }

# Adiciona classes.dex ao APK (zip sem compressão de cabeçalho especial).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
# Fontes (assets). O aapt2 -A no Windows grava o caminho com '\', que o Android não acha.
Get-ChildItem -Recurse -File "$here\assets" | ForEach-Object {
    $name = 'assets/' + $_.FullName.Substring("$here\assets\".Length).Replace('\', '/')
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name)
}
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign falhou' }

$ks = "$here\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jbr\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias modtv `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=MODTV Launcher' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$out\modtv-launcher.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner falhou' }
& "$bt\apksigner.bat" verify "$out\modtv-launcher.apk"
if ($LASTEXITCODE) { throw 'verificação da assinatura falhou' }
Write-Output "APK: $out\modtv-launcher.apk"
