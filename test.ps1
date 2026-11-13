$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $projectRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$testRoot = Join-Path $projectRoot 'build\test-classes'
$mainClasspath = "$projectRoot\build\classes;$projectRoot\build\lib\flatlaf-3.7.2.jar;$projectRoot\build\lib\flatlaf-extras-3.7.2.jar;$projectRoot\build\lib\jsvg-2.1.0.jar"
if (Test-Path $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$tests = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -cp $mainClasspath -d $testRoot $tests
if ($LASTEXITCODE -ne 0) { throw 'La compilacion de pruebas fallo.' }
$testClasspath = "$testRoot;$mainClasspath"
& java -cp $testClasspath com.nexodb.util.SqlTextTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas fallaron.' }
& java -cp $testClasspath com.nexodb.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del nucleo fallaron.' }
& java -cp $testClasspath com.nexodb.db.JdbcSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de credenciales JDBC fallaron.' }
& java -cp $testClasspath com.nexodb.ui.CompletionInteractionTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de autocompletado fallo.' }
& java -cp $testClasspath com.nexodb.db.ZeroDateTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de fechas cero fallo.' }
& java -cp $testClasspath com.nexodb.util.SecretStoreTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba del almacen seguro de contrasenas fallo.' }
& java -cp $testClasspath com.nexodb.util.DataExporterTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de exportacion de datos fallo.' }
& java -cp $testClasspath com.nexodb.db.DriverDownloadServiceTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba del gestor de adaptadores JDBC fallo.' }

