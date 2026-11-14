param([string]$SdkPath=$env:ANDROID_HOME,[string]$GradlePath='', [switch]$Debug)
$ErrorActionPreference='Stop'
$androidRoot=$PSScriptRoot
$repository=Split-Path -Parent $androidRoot
if(-not $SdkPath){
    $candidates=@((Join-Path $env:LOCALAPPDATA 'Android/Sdk'),(Join-Path $env:LOCALAPPDATA 'NoemaAndroidToolchain/sdk'))
    foreach($package in @(Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'Packages') -Directory -Filter 'OpenAI.Codex_*' -ErrorAction SilentlyContinue)){$candidates+=Join-Path $package.FullName 'LocalCache/Local/NoemaAndroidToolchain/sdk'}
    $SdkPath=$candidates | Where-Object {Test-Path -LiteralPath (Join-Path $_ 'platforms/android-35/android.jar')} | Select-Object -First 1
}
if(-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-35/android.jar'))){throw 'Indica -SdkPath con un Android SDK que incluya la plataforma 35.'}
if(-not $GradlePath){
    $distribution=Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.11.1-bin'
    $GradlePath=Get-ChildItem -LiteralPath $distribution -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if(-not $GradlePath){throw 'Instala Gradle 8.11.1 o indica -GradlePath con la ruta de gradle.bat.'}
[IO.File]::WriteAllText((Join-Path $androidRoot 'local.properties'),('sdk.dir='+$SdkPath.Replace('\','/')+"`n"),[Text.UTF8Encoding]::new($false))
$private=Join-Path $repository '.signing'
$store=Join-Path $private 'android-release.jks'
$settings=Join-Path $private 'android-signing.properties'
if(-not $Debug -and -not (Test-Path -LiteralPath $settings)){
    if(Test-Path -LiteralPath $store){throw 'Existe la clave de firma Android pero falta su configuración privada. Recupérala para conservar las actualizaciones.'}
    New-Item -ItemType Directory -Path $private -Force | Out-Null
    $random=New-Object byte[] 32
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($random);$rng.Dispose()
    $secret=[Convert]::ToBase64String($random).TrimEnd('=').Replace('+','-').Replace('/','_')
    $env:NEXODB_ANDROID_KEY_PASSWORD=$secret
    try{
        $keytool=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/keytool.exe'}else{'keytool'}
        & $keytool -genkeypair -keystore $store -storetype JKS -alias nexodb-android -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NexoDB Studio, O=NexoDB, C=EC' -storepass:env NEXODB_ANDROID_KEY_PASSWORD -keypass:env NEXODB_ANDROID_KEY_PASSWORD
        if($LASTEXITCODE -ne 0){throw 'No se pudo crear la clave de firma Android.'}
        [IO.File]::WriteAllText($settings,"storeFile=$($store.Replace('\','/'))`nstorePassword=$secret`nkeyAlias=nexodb-android`nkeyPassword=$secret`n",[Text.UTF8Encoding]::new($false))
    }finally{Remove-Item Env:NEXODB_ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue;$secret=$null;[Array]::Clear($random,0,$random.Length)}
}
# Keep generated output out of OneDrive: its read-only directory attributes break Gradle cleanup.
$buildRoot=Join-Path ([IO.Path]::GetTempPath()) ('nexodb-android-build-'+[Math]::Abs($repository.GetHashCode()))
$variant=if($Debug){'Debug'}else{'Release'}
& $GradlePath -p $androidRoot "-PnexodbBuildRoot=$buildRoot" --project-cache-dir (Join-Path $buildRoot 'project-cache') ":app:assemble$variant" --no-daemon --console=plain
if($LASTEXITCODE -ne 0){throw 'La compilación Android falló.'}
$source=Join-Path $buildRoot ("app/outputs/apk/$($variant.ToLower())/app-$($variant.ToLower()).apk")
$version=(Get-Content -LiteralPath (Join-Path $androidRoot 'VERSION') -Raw).Trim()
$parts=$version.Split('.')
$versionCode=([int]$parts[0]*10000)+([int]$parts[1]*100)+[int]$parts[2]
$target=Join-Path $repository ("dist/AdisDB-$version-android"+$(if($Debug){'-debug'}else{''})+'.apk')
Copy-Item -LiteralPath $source -Destination $target -Force
if(-not $Debug){
    & (Join-Path $SdkPath 'build-tools/35.0.0/apksigner.bat') verify --verbose $target
    if($LASTEXITCODE -ne 0){throw 'La APK no superó la verificación de firma.'}
}
$hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($target))" | Set-Content -LiteralPath ($target+'.sha256') -Encoding ASCII
if(-not $Debug){
    $manifest=[ordered]@{version=$version;versionCode=$versionCode;downloadUrl='https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/'+[IO.Path]::GetFileName($target);sha256=$hash;size=(Get-Item -LiteralPath $target).Length;notes='AdisDB, producto de la suite AdisOne: nueva marca e icono. Conserva conexiones, consultas, licencia, firma e identificador de la aplicacion.'} | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $repository 'dist/android-update-manifest.json'),$manifest+"`n",[Text.UTF8Encoding]::new($false))
}
Write-Output "APK lista: $target"
