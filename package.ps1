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
$appRoot = Join-Path $releaseRoot 'NexoDB Studio'
$packageRoot = Join-Path ([IO.Path]::GetTempPath()) ("nexodb-package-" + $version)
$iconPath = Join-Path $projectRoot 'build\nexodb.ico'
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
    (Join-Path $projectRoot 'resources\com\nexodb\assets\nexodb-icon.png') `
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
    --name 'NexoDB Studio' `
    --app-version $version `
    --vendor 'NexoDB' `
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
$sourceApp = Join-Path $packageRoot 'NexoDB Studio'
$copied = $false
$copyError = $null
for ($attempt = 1; $attempt -le 8 -and -not $copied; $attempt++) {
    try {
        if (Test-Path -LiteralPath $appRoot) { Remove-Item -LiteralPath $appRoot -Recurse -Force }
        Copy-Item -LiteralPath $sourceApp -Destination $appRoot -Recurse -Force
        if (-not (Test-Path -LiteralPath (Join-Path $appRoot 'NexoDB Studio.exe'))) {
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
foreach ($legacyName in @('NexoDB-Studio-0.1.0-win64.zip', 'NexoDB-Studio-0.2.0-win64.zip', 'NexoDB-Studio-0.3.0-win64.zip', 'NexoDB-Studio-0.3.1-win64.zip')) {
    $legacyZipPath = Join-Path $distRoot $legacyName
    if (Test-Path $legacyZipPath) { Remove-Item -LiteralPath $legacyZipPath -Force }
}
if ($signer) { Invoke-NexoSignFile $signer (Join-Path $appRoot 'NexoDB Studio.exe') }
$zipPath = Join-Path $distRoot ("NexoDB-Studio-$version-win64.zip")
if (Test-Path $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $appRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$downloadUrl = if ($UpdateBaseUrl) { $UpdateBaseUrl.TrimEnd('/') + '/' + $zipPath.Split([IO.Path]::DirectorySeparatorChar)[-1] } else { '' }
$manifest = [ordered]@{
    version = $version
    downloadUrl = $downloadUrl
    sha256 = $zipHash
    notes = "NexoDB Studio ${version}: depuracion por instruccion con inspeccion de variables al pasar el cursor, tablas JSON, SELECT en tiempo real e historial. Editor compatible con DELIMITER/USE/DROP/CREATE de SQLyog. Errores con linea marcada y explicacion. Exportar y copiar resultados en Excel, TXT, CSV y SQL."
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $distRoot 'update-manifest.json') -Encoding utf8
Write-Host "Aplicacion portable lista: $appRoot\NexoDB Studio.exe"
Write-Host "Paquete para distribuir: $zipPath"
Write-Host "Manifiesto de actualizacion: $distRoot\update-manifest.json"

