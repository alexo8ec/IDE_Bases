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
$updatedService = $versionPattern.Replace($updateService, ('CURRENT_VERSION = "' + $desktopVersion + '"'), 1)
if ($updatedService -notmatch ('CURRENT_VERSION\s*=\s*"' + [regex]::Escape($desktopVersion) + '"')) {
    throw 'No se pudo sincronizar CURRENT_VERSION con VERSION.'
}
[IO.File]::WriteAllText($updateServicePath, $updatedService, [Text.UTF8Encoding]::new($false))

$readmePath = Join-Path $root 'README.md'
$readme = [IO.File]::ReadAllText($readmePath)
$readmePattern = [regex]'La versión actual de escritorio es \*\*\d+\.\d+\.\d+\*\*\.(.*?)Android mantiene su versión independiente \*\*\d+\.\d+\.\d+\*\*\.'
$readme = $readmePattern.Replace(
    $readme,
    ('La versión actual de escritorio es **' + $desktopVersion + '**.$1Android mantiene su versión independiente **' + $androidVersion + '**.'),
    1
)
[IO.File]::WriteAllText($readmePath, $readme, [Text.UTF8Encoding]::new($false))

& (Join-Path $root 'installer.ps1') -DownloadBaseUrl $DownloadBaseUrl
if ($LASTEXITCODE -ne 0) { throw 'Falló la entrega de Windows.' }
& (Join-Path $root 'android\build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falló la entrega de Android.' }
& (Join-Path $root 'tools\verify-windows.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Falló la verificación de Windows.' }

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
$mobile = Get-Content -LiteralPath (Join-Path $root 'dist\android-update-manifest.json') -Raw | ConvertFrom-Json
if ($mobile.version -ne $androidVersion -or -not $mobile.downloadUrl -or -not $mobile.sha256) {
    throw 'El manifiesto Android no contiene versión, URL pública y SHA-256 coherentes.'
}

Write-Host "Entrega verificada: AdisDB $desktopVersion y Android $androidVersion."
