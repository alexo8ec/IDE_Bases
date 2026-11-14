param(
    [string]$DownloadBaseUrl = '',
    [string]$UpdateManifestUrl = '',
    [string]$CertificateThumbprint = $env:NEXODB_SIGN_CERT_THUMBPRINT,
    [switch]$RequireSigning
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $projectRoot 'tools/CodeSigning.ps1')
if ($RequireSigning -and -not $CertificateThumbprint) { throw 'La distribucion publica requiere un certificado de firma. Configura NEXODB_SIGN_CERT_THUMBPRINT.' }
$signer = if ($CertificateThumbprint) { Get-NexoSigner $CertificateThumbprint } else { $null }
$version = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'VERSION')).Trim()
$wixRoot = Join-Path $projectRoot '.tools\wix314'
$wixArchive = Join-Path $projectRoot '.tools\wix314-binaries.zip'
$wixUrl = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
$wixHash = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
$embeddedManifestUrl = if ($UpdateManifestUrl) {
    $UpdateManifestUrl.Trim()
} elseif ($DownloadBaseUrl) {
    $DownloadBaseUrl.TrimEnd('/') + '/update-manifest.json'
} else { '' }

if (-not (Test-Path (Join-Path $wixRoot 'candle.exe'))) {
    New-Item -ItemType Directory -Path $wixRoot -Force | Out-Null
    Invoke-WebRequest -Uri $wixUrl -OutFile $wixArchive
    $downloadedHash = (Get-FileHash -LiteralPath $wixArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloadedHash -ne $wixHash) { throw "El paquete WiX descargado no superó la verificación SHA-256." }
    Expand-Archive -LiteralPath $wixArchive -DestinationPath $wixRoot -Force
}

& (Join-Path $projectRoot 'package.ps1') -UpdateBaseUrl $DownloadBaseUrl -UpdateManifestUrl $embeddedManifestUrl -CertificateThumbprint $CertificateThumbprint -RequireSigning:$RequireSigning
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$staging = Join-Path $projectRoot 'build\installer-input'
$installerRoot = Join-Path $projectRoot 'dist\installer'
$iconPath = Join-Path $projectRoot 'build\adisdb.ico'
if (Test-Path $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null
New-Item -ItemType Directory -Path $installerRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\nexodb-studio.jar') -Destination $staging
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\lib') -Destination $staging -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\drivers') -Destination $staging -Recurse
$existingInstaller = Join-Path $installerRoot ("AdisDB-$version.exe")
if (Test-Path -LiteralPath $existingInstaller) { Remove-Item -LiteralPath $existingInstaller -Force }

$previousPath = $env:PATH
try {
    $env:PATH = "$wixRoot;$previousPath"
    & jpackage `
        --type exe `
        --name 'AdisDB' `
        --app-version $version `
        --vendor 'Alex Mite Salazar' `
        --copyright 'Copyright © 2026 Alex Mite Salazar' `
        --description 'Cliente profesional y universal de bases de datos' `
        --app-image (Join-Path $projectRoot "dist/$version/AdisDB") `
        --icon $iconPath `
        --dest $installerRoot `
        --install-dir 'AdisDB' `
        --win-per-user-install `
        --win-dir-chooser `
        --win-menu `
        --win-menu-group 'AdisDB' `
        --win-shortcut `
        --win-shortcut-prompt `
        --win-upgrade-uuid '8b7eafbb-43c1-4b70-a75c-fc964e02ea6e'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el instalador de Windows.' }
} finally {
    $env:PATH = $previousPath
}

$installer = Get-ChildItem -LiteralPath $installerRoot -Filter '*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$portable = Join-Path $projectRoot "dist\AdisDB-$version-win64.zip"
if (-not $installer) { throw 'No se encontró el instalador generado.' }
if ($signer) { Invoke-NexoSignFile $signer $installer.FullName }
$installerHash = (Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$portableHash = (Get-FileHash -LiteralPath $portable -Algorithm SHA256).Hash.ToLowerInvariant()
$base = $DownloadBaseUrl.TrimEnd('/')
$release = [ordered]@{
    product = 'AdisDB'
    version = $version
    publisher = 'Alex Mite Salazar'
    contact = 'alexo8ec@hotmail.com'
    installer = [ordered]@{
        file = $installer.Name
        url = if ($base) { "$base/installer/$($installer.Name.Replace(' ', '%20'))" } else { '' }
        sha256 = $installerHash
    }
    portable = [ordered]@{
        file = Split-Path -Leaf $portable
        url = if ($base) { "$base/$(Split-Path -Leaf $portable)" } else { '' }
        sha256 = $portableHash
    }
}
$release | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $projectRoot 'dist\release-manifest.json') -Encoding utf8
@(
    "$installerHash  $($installer.Name)"
    "$portableHash  $(Split-Path -Leaf $portable)"
) | Set-Content -LiteralPath (Join-Path $projectRoot 'dist\SHA256SUMS.txt') -Encoding ascii

Write-Host "Instalador profesional listo: $($installer.FullName)"
Write-Host "Portable: $portable"
Write-Host "Manifiesto para el servidor: $(Join-Path $projectRoot 'dist\release-manifest.json')"
