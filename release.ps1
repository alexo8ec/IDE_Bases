param(
    [string]$DownloadBaseUrl = 'https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktopVersion = (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim()
$androidVersion = (Get-Content -LiteralPath (Join-Path $root 'android\VERSION') -Raw).Trim()
if ($desktopVersion -notmatch '^\d+\.\d+\.\d+$' -or $androidVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw 'VERSION y android/VERSION deben usar major.minor.patch.'
}

$updateServicePath = Join-Path $root 'src\com\nexodb\update\UpdateService.java'
$updateService = [IO.File]::ReadAllText($updateServicePath)
$versionPattern = [regex]'CURRENT_VERSION\s*=\s*"\d+\.\d+\.\d+"'
$dynamicVersionPattern = 'CURRENT_VERSION\s*=\s*com\.nexodb\.util\.AppVersion\.current\(\)'
if ($updateService -match $dynamicVersionPattern) {
    $updatedService = $updateService
} else {
    $updatedService = $versionPattern.Replace($updateService, ('CURRENT_VERSION = "' + $desktopVersion + '"'), 1)
    if ($updatedService -notmatch ('CURRENT_VERSION\s*=\s*"' + [regex]::Escape($desktopVersion) + '"')) {
        throw 'No se pudo sincronizar CURRENT_VERSION con VERSION.'
    }
}
[IO.File]::WriteAllText($updateServicePath, $updatedService, [Text.UTF8Encoding]::new($false))

$readmePath = Join-Path $root 'README.md'
$readme = [IO.File]::ReadAllText($readmePath)
$readme = [regex]::Replace($readme, '(?m)^(.+?escritorio es )\*\*\d+\.\d+\.\d+\*\*', ('$1**' + $desktopVersion + '**'), 1)
$readme = [regex]::Replace($readme, '(Android mantiene.+?)\*\*\d+\.\d+\.\d+\*\*', ('$1**' + $androidVersion + '**'), 1)
if ($readme -notmatch ('escritorio es \*\*' + [regex]::Escape($desktopVersion) + '\*\*') -or
    $readme -notmatch ('Android mantiene.+?\*\*' + [regex]::Escape($androidVersion) + '\*\*')) {
    throw 'No se pudo sincronizar README.md con las versiones de escritorio y Android.'
}
[IO.File]::WriteAllText($readmePath, $readme, [Text.UTF8Encoding]::new($false))

& (Join-Path $root 'installer.ps1') -DownloadBaseUrl $DownloadBaseUrl
if ($LASTEXITCODE -ne 0) { throw 'Falló la entrega de Windows.' }
& (Join-Path $root 'android\build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falló la entrega de Android.' }
& (Join-Path $root 'tools\verify-windows.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falló la verificación de Windows.' }

# Linux y macOS se generan con runtimes oficiales ya verificados para cada CPU.
# Los ZIP macOS conservan permisos Unix y contienen AdisDB.app completo para la actualización interna.
& python (Join-Path $root 'tools\package-platforms.py') --offline `
    --platform linux-x64 --platform linux-arm64 --platform macos-x64 --platform macos-arm64
if ($LASTEXITCODE -ne 0) { throw 'Falló la entrega de Linux/macOS.' }
$windowsPackage = Join-Path $root "dist\AdisDB-$desktopVersion-win64.zip"
$linuxIntelPackage = Join-Path $root "dist\AdisDB-$desktopVersion-linux-x64.deb"
$linuxArmPackage = Join-Path $root "dist\AdisDB-$desktopVersion-linux-arm64.deb"
$macIntelPackage = Join-Path $root "dist\AdisDB-$desktopVersion-macos-x64.zip"
$macArmPackage = Join-Path $root "dist\AdisDB-$desktopVersion-macos-arm64.zip"
& python (Join-Path $root 'tools\create-update-manifest.py') `
    --base-url $DownloadBaseUrl `
    --windows-x64 $windowsPackage `
    --linux-x64 $linuxIntelPackage `
    --linux-arm64 $linuxArmPackage `
    --macos-x64 $macIntelPackage `
    --macos-arm64 $macArmPackage `
    --notes "AdisDB ${desktopVersion}: credenciales de tareas verificadas, sincronizacion segura tras conectar y reintento externo de respaldos interrumpidos al cerrar."
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el manifiesto multiplataforma.' }
& python (Join-Path $root 'tools\verify-platforms.py')
if ($LASTEXITCODE -ne 0) { throw 'Falló la verificación cruzada de los paquetes Linux/macOS.' }

$localProperties = Get-Content -LiteralPath (Join-Path $root 'android\local.properties') | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
if (-not $localProperties) { throw 'android/local.properties no contiene sdk.dir.' }
$sdk = $localProperties.Substring('sdk.dir='.Length).Replace('/', '\')
$previous = Get-ChildItem -LiteralPath (Join-Path $root 'dist') -Filter 'AdisDB-*-android.apk' |
    Where-Object { $_.Name -ne "AdisDB-$androidVersion-android.apk" } |
    Sort-Object Name -Descending |
    Select-Object -First 1
$verifyArgs = @((Join-Path $root 'tools\verify-android.py'), '--sdk', $sdk)
if ($previous) { $verifyArgs += @('--previous', $previous.FullName) }
& python @verifyArgs
if ($LASTEXITCODE -ne 0) { throw 'Falló la verificación de Android.' }

$manifest = Get-Content -LiteralPath (Join-Path $root 'dist\update-manifest.json') -Raw | ConvertFrom-Json
if ($manifest.version -ne $desktopVersion -or -not $manifest.downloadUrl -or -not $manifest.sha256) {
    throw 'El manifiesto de escritorio no contiene versión, URL pública y SHA-256 coherentes.'
}
foreach ($platform in @('windows-x64', 'linux-x64', 'linux-arm64', 'macos-x64', 'macos-arm64')) {
    $asset = $manifest.platforms.$platform
    if (-not $asset -or -not $asset.downloadUrl -or $asset.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "El manifiesto no contiene un paquete válido para $platform."
    }
}
$mobile = Get-Content -LiteralPath (Join-Path $root 'dist\android-update-manifest.json') -Raw | ConvertFrom-Json
if ($mobile.version -ne $androidVersion -or -not $mobile.downloadUrl -or -not $mobile.sha256) {
    throw 'El manifiesto Android no contiene versión, URL pública y SHA-256 coherentes.'
}

$releaseFiles = @(
    (Join-Path $root "dist\installer\AdisDB-$desktopVersion.exe"),
    $windowsPackage, $linuxIntelPackage, $linuxArmPackage, $macIntelPackage, $macArmPackage,
    (Join-Path $root "dist\AdisDB-$androidVersion-android.apk")
)
$checksumLines = foreach ($file in $releaseFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Falta el archivo de entrega: $file" }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($file))"
}
[IO.File]::WriteAllText((Join-Path $root 'dist\SHA256SUMS.txt'),
    (($checksumLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))

Write-Host "Entrega verificada: AdisDB $desktopVersion para Windows, Linux x64/ARM64 y macOS Intel/Apple Silicon; Android $androidVersion."
