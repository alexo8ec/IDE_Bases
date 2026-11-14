$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $projectRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$testRoot = Join-Path $projectRoot 'build\test-classes'
$mainClasspath = "$projectRoot\build\classes;$projectRoot\build\lib\flatlaf-3.7.2.jar;$projectRoot\build\lib\flatlaf-extras-3.7.2.jar;$projectRoot\build\lib\jsvg-2.1.0.jar"
if (Test-Path $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$tests = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$issuerSupport = @((Join-Path $projectRoot 'license-issuer/src/com/nexodb/issuer/IssuerAuthority.java'), (Join-Path $projectRoot 'license-issuer/src/com/nexodb/issuer/IssuerVault.java'))
& javac -encoding UTF-8 -cp $mainClasspath -d $testRoot $tests $issuerSupport
if ($LASTEXITCODE -ne 0) { throw 'La compilacion de pruebas fallo.' }
$testClasspath = "$testRoot;$mainClasspath"
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.util.SqlTextTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del nucleo fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.JdbcSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de credenciales JDBC fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.MysqlTableDesignTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del diseno de tablas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.PostgresSupportTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de seleccion de bases PostgreSQL fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.CompletionInteractionTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de autocompletado fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.ZeroDateTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de fechas cero fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.util.SecretStoreTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba del almacen seguro de contrasenas fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.util.DataExporterTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de exportacion de datos fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.DriverDownloadServiceTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba del gestor de adaptadores JDBC fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.update.UpdateServiceTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del actualizador fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.AppearanceTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de idiomas y temas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.util.SecureDownloadTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de descarga HTTPS fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.LanguageSwitchTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de cambio de idioma en vivo fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.LanguageWindowTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de idioma en la ventana real fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.SchemaMetadataReaderTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de lectura de esquemas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.ComparisonSelectionTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de seleccion persistente fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.ConnectionFeedbackTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de resultado de conexion fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.ComparisonWindowTest
if ($LASTEXITCODE -ne 0) { throw 'La prueba de casillas en la ventana de comparacion fallo.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.QueryEditSqlTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de consultas editables fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.SqlColumnCompletionTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de columnas en autocompletado fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.SqlDraftPersistenceTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de guardado de consultas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.QueryTabsTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de aislamiento de consultas fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.SqlScriptReaderTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del lector de archivos SQL fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.SqlDocumentTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de archivos SQL y deshacer/rehacer fallaron.' }
& java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.licensing.LicenseTest
if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de licencias y autoridad cifrada fallaron.' }
if (Test-Path -LiteralPath (Join-Path $projectRoot 'build/h2-test.jar')) {
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.SqlJoinCompletionTest
    if ($LASTEXITCODE -ne 0) { throw 'La prueba de sugerencias JOIN fallo.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.JdbcRowLimitTest
    if ($LASTEXITCODE -ne 0) { throw 'La prueba de consultas sin limite fallo.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.ForeignKeyDefinitionTest
    if ($LASTEXITCODE -ne 0) { throw 'La prueba de claves foraneas fallo.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.ForeignKeyDesignerTest
    if ($LASTEXITCODE -ne 0) { throw 'La prueba de relaciones con arrastre fallo.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.DiagramCardSizingTest
    if ($LASTEXITCODE -ne 0) { throw 'La prueba de tamano y cierre del diagrama fallo.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.QueryEditIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de escritura y transacciones fallaron.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.QueryGridIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de la cuadricula editable fallaron.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.VisualToolsTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas del constructor visual y busqueda fallaron.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.db.CsvJobsIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de importacion CSV y tareas fallaron.' }
    & java '-Dnexodb.disableDraftPersistence=true' '-Djava.awt.headless=true' -cp $testClasspath com.nexodb.ui.QuerySafetyTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas de seguridad de consultas fallaron.' }
    & java '-Dnexodb.disableDraftPersistence=true' -cp $testClasspath com.nexodb.ui.WorkbenchToolsWindowTest
    if ($LASTEXITCODE -ne 0) { throw 'Las pruebas visuales de herramientas fallaron.' }
}

