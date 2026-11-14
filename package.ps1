param(
    [string]$UpdateBaseUrl = '',
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
& (Join-Path $projectRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$distRoot = Join-Path $projectRoot 'dist'
$releaseRoot = Join-Path $distRoot $version
$appRoot = Join-Path $releaseRoot 'AdisDB'
$packageRoot = Join-Path ([IO.Path]::GetTempPath()) ("nexodb-package-" + $version)
$iconPath = Join-Path $projectRoot 'build\adisdb.ico'
$productionInput = Join-Path $projectRoot 'build\package-input'
if (Test-Path -LiteralPath $productionInput) { Remove-Item -LiteralPath $productionInput -Recurse -Force }
New-Item -ItemType Directory -Path $productionInput -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\nexodb-studio.jar') -Destination $productionInput
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\lib') -Destination $productionInput -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'build\drivers') -Destination $productionInput -Recurse
$embeddedManifestUrl = if ($UpdateManifestUrl) {
    $UpdateManifestUrl.Trim()
} elseif ($UpdateBaseUrl) {
    $UpdateBaseUrl.TrimEnd('/') + '/update-manifest.json'
} else { '' }
& java -cp (Join-Path $projectRoot 'build\classes') com.nexodb.tools.IconBuilder `
    (Join-Path $projectRoot 'resources\com\nexodb\assets\adisdb-icon.png') `
    $iconPath
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el icono de Windows.' }
if (Test-Path $appRoot) {
    Remove-Item -LiteralPath $appRoot -Recurse -Force
}
if (Test-Path $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
New-Item -ItemType Directory -Path $packageRoot -Force | Out-Null

& jpackage `
    --type app-image `
    --name 'AdisDB' `
    --app-version $version `
    --vendor 'AdisOne' `
    --description 'Cliente universal de bases de datos' `
    --input $productionInput `
    --main-jar 'nexodb-studio.jar' `
    --main-class 'com.nexodb.App' `
    --icon $iconPath `
    --dest $packageRoot `
    --add-modules 'java.desktop,java.sql,java.prefs,java.naming,java.net.http,java.security.jgss,jdk.security.auth,jdk.crypto.ec' `
    --java-options '-Dfile.encoding=UTF-8' `
    --java-options '-Djava.net.useSystemProxies=true' `
    --java-options '-Dsun.java2d.d3d=false' `
    --java-options '-Dsun.java2d.opengl=false' `
    --java-options '-Dsun.security.jgss.native=true' `
    --java-options "-Dnexodb.updateManifest=$embeddedManifestUrl" `
    --java-options '-Xms16m' `
    --java-options '-Xmx256m' `
    --java-options '-XX:+UseSerialGC'

if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la aplicacion portable.' }
$sourceApp = Join-Path $packageRoot 'AdisDB'
$copied = $false
$copyError = $null
for ($attempt = 1; $attempt -le 8 -and -not $copied; $attempt++) {
    try {
        if (Test-Path -LiteralPath $appRoot) { Remove-Item -LiteralPath $appRoot -Recurse -Force }
        Copy-Item -LiteralPath $sourceApp -Destination $appRoot -Recurse -Force
        if (-not (Test-Path -LiteralPath (Join-Path $appRoot 'AdisDB.exe'))) {
            throw 'El ejecutable no apareció en la copia de salida.'
        }
        $copied = $true
    } catch {
        $copyError = $_
        Start-Sleep -Milliseconds 750
    }
}
if (-not $copied) { throw "Windows mantuvo bloqueado el ejecutable generado: $copyError" }
Remove-Item -LiteralPath $packageRoot -Recurse -Force -ErrorAction SilentlyContinue
foreach ($legacyName in @('AdisDB-0.1.0-win64.zip', 'AdisDB-0.2.0-win64.zip', 'AdisDB-0.3.0-win64.zip', 'AdisDB-0.3.1-win64.zip')) {
    $legacyZipPath = Join-Path $distRoot $legacyName
    if (Test-Path $legacyZipPath) { Remove-Item -LiteralPath $legacyZipPath -Force }
}
if ($signer) { Invoke-NexoSignFile $signer (Join-Path $appRoot 'AdisDB.exe') }
$zipPath = Join-Path $distRoot ("AdisDB-$version-win64.zip")
if (Test-Path $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $appRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal
# Older NexoDB updaters restart the original executable after copying this ZIP.
# Ship compatible launcher/config aliases in the update ZIP; the installed product is AdisDB.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($zipPath,[IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $appRoot 'AdisDB.exe'),'NexoDB Studio.exe') | Out-Null
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $appRoot 'app/AdisDB.cfg'),'app/NexoDB Studio.cfg') | Out-Null
} finally { $archive.Dispose() }
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$downloadUrl = if ($UpdateBaseUrl) { $UpdateBaseUrl.TrimEnd('/') + '/' + $zipPath.Split([IO.Path]::DirectorySeparatorChar)[-1] } else { '' }
$manifest = [ordered]@{
    version = $version
    downloadUrl = $downloadUrl
    sha256 = $zipHash
    notes = "AdisDB ${version}: corrige el autocompletado SQL con clic, Enter y Tab sin duplicar el prefijo escrito. Esc cierra el cuadro y descarta sugerencias pendientes. Conserva licencias, conexiones y preferencias."
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $distRoot 'update-manifest.json') -Encoding utf8
Write-Host "Aplicacion portable lista: $appRoot\AdisDB.exe"
Write-Host "Paquete para distribuir: $zipPath"
Write-Host "Manifiesto de actualizacion: $distRoot\update-manifest.json"

