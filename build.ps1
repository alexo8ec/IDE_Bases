$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot 'src'
$resourceRoot = Join-Path $projectRoot 'resources'
$buildRoot = Join-Path $projectRoot 'build'
$classesRoot = Join-Path $buildRoot 'classes'
$jarPath = Join-Path $buildRoot 'nexodb-studio.jar'
$bundledDriversRoot = Join-Path $buildRoot 'drivers'
$bundledLibrariesRoot = Join-Path $buildRoot 'lib'

if (Test-Path $classesRoot) {
    Remove-Item -LiteralPath $classesRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null

$mysqlDriverSource = Join-Path $projectRoot 'third-party\mysql-connector-j-26.10.0\mysql-connector-j-26.10.0.jar'
if (-not (Test-Path -LiteralPath $mysqlDriverSource)) {
    throw 'Falta el controlador incluido de MySQL. Revisa third-party\mysql-connector-j-26.10.0.'
}
$mariaDbDriverSource = Join-Path $projectRoot 'third-party\mariadb-java-client-4.0.0\mariadb-java-client-4.0.0.jar'
if (-not (Test-Path -LiteralPath $mariaDbDriverSource)) {
    throw 'Falta el controlador incluido de MariaDB. Revisa third-party\mariadb-java-client-4.0.0.'
}
$mariaDbWindowsDriverSource = Join-Path $projectRoot 'third-party\mariadb-java-client-3.5.11\mariadb-java-client-3.5.11.jar'
$windowsAuthSources = @(
    (Join-Path $projectRoot 'third-party\waffle-jna-3.6.0\waffle-jna-3.6.0.jar'),
    (Join-Path $projectRoot 'third-party\windows-auth-dependencies\jna-5.18.1.jar'),
    (Join-Path $projectRoot 'third-party\windows-auth-dependencies\jna-platform-5.18.1.jar'),
    (Join-Path $projectRoot 'third-party\windows-auth-dependencies\slf4j-api-2.0.17.jar'),
    (Join-Path $projectRoot 'third-party\windows-auth-dependencies\caffeine-3.2.3.jar'),
    (Join-Path $projectRoot 'third-party\windows-auth-dependencies\checker-qual-3.53.1.jar')
)
if (-not (Test-Path -LiteralPath $mariaDbWindowsDriverSource) -or ($windowsAuthSources | Where-Object { -not (Test-Path -LiteralPath $_) })) {
    throw 'Faltan las librerias para autenticacion integrada de Windows.'
}
$flatLafSource = Join-Path $projectRoot 'third-party\flatlaf-3.7.2\flatlaf-3.7.2.jar'
if (-not (Test-Path -LiteralPath $flatLafSource)) {
    throw 'Falta FlatLaf. Revisa third-party\flatlaf-3.7.2.'
}
$flatLafExtrasSource = Join-Path $projectRoot 'third-party\flatlaf-extras-3.7.2\flatlaf-extras-3.7.2.jar'
$jsvgSource = Join-Path $projectRoot 'third-party\jsvg-2.1.0\jsvg-2.1.0.jar'
if (-not (Test-Path -LiteralPath $flatLafExtrasSource) -or -not (Test-Path -LiteralPath $jsvgSource)) {
    throw 'Faltan las librerias para renderizar los logotipos SVG.'
}
New-Item -ItemType Directory -Path $bundledDriversRoot -Force | Out-Null
New-Item -ItemType Directory -Path $bundledLibrariesRoot -Force | Out-Null
Copy-Item -LiteralPath $mysqlDriverSource -Destination (Join-Path $bundledDriversRoot 'mysql-connector-j-26.10.0.jar') -Force
Copy-Item -LiteralPath $mariaDbDriverSource -Destination (Join-Path $bundledDriversRoot 'mariadb-java-client-4.0.0.jar') -Force
Copy-Item -LiteralPath $mariaDbWindowsDriverSource -Destination (Join-Path $bundledDriversRoot 'mariadb-java-client-3.5.11.jar') -Force
Copy-Item -LiteralPath $flatLafSource -Destination (Join-Path $bundledLibrariesRoot 'flatlaf-3.7.2.jar') -Force
Copy-Item -LiteralPath $flatLafExtrasSource -Destination (Join-Path $bundledLibrariesRoot 'flatlaf-extras-3.7.2.jar') -Force
Copy-Item -LiteralPath $jsvgSource -Destination (Join-Path $bundledLibrariesRoot 'jsvg-2.1.0.jar') -Force
foreach ($windowsAuthSource in $windowsAuthSources) {
    Copy-Item -LiteralPath $windowsAuthSource -Destination (Join-Path $bundledLibrariesRoot (Split-Path -Leaf $windowsAuthSource)) -Force
}

$sources = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
if (-not $sources) {
    throw 'No se encontraron archivos Java.'
}

$compileClasspath = "$flatLafSource;$flatLafExtrasSource;$jsvgSource"
& javac --release 17 -encoding UTF-8 -cp $compileClasspath -d $classesRoot $sources
if ($LASTEXITCODE -ne 0) { throw 'La compilacion fallo.' }

if (Test-Path -LiteralPath $resourceRoot) {
    Copy-Item -Path (Join-Path $resourceRoot '*') -Destination $classesRoot -Recurse -Force
}

$manifestPath = Join-Path $buildRoot 'MANIFEST.MF'
@(
    'Manifest-Version: 1.0'
    'Main-Class: com.nexodb.App'
    'Class-Path: lib/flatlaf-3.7.2.jar lib/flatlaf-extras-3.7.2.jar lib/jsvg-2.1.0.jar lib/waffle-jna-3.6.0.jar lib/jna-5.18.1.jar lib/jna-platform-5.18.1.jar lib/slf4j-api-2.0.17.jar lib/caffeine-3.2.3.jar lib/checker-qual-3.53.1.jar'
    ''
) | Set-Content -LiteralPath $manifestPath -Encoding ascii

& jar "-J-Djava.io.tmpdir=$buildRoot" --create --file $jarPath --manifest $manifestPath -C $classesRoot .
if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el JAR.' }

$reportManifest = Join-Path $buildRoot 'REPORT-MANIFEST.MF'
@('Manifest-Version: 1.0', 'Automatic-Module-Name: com.nexodb.reporting', '') | Set-Content -LiteralPath $reportManifest -Encoding ascii
& jar "-J-Djava.io.tmpdir=$buildRoot" --create --file (Join-Path $buildRoot 'nexodb-reporting.jar') --manifest $reportManifest -C $classesRoot 'com/nexodb/reporting'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el modulo de reporteria.' }

Write-Host "Compilacion lista: $jarPath"

