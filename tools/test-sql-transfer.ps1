param([string]$MariaDbBin = 'C:\Program Files\MariaDB 12.3\bin', [switch]$SkipGeneralSuite)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$data = [IO.Path]::GetFullPath((Join-Path $root 'build/sql-transfer-test-data'))
if (-not $data.StartsWith([IO.Path]::GetFullPath((Join-Path $root 'build')) + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe test data directory' }
foreach ($program in @('mariadb-install-db.exe', 'mariadbd.exe', 'mariadb-admin.exe', 'mariadb-dump.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $MariaDbBin $program))) { throw "Missing test dependency: $program" }
}
# Never attach to an existing server, load a personal my.ini, or install a Windows service.
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,43179)
try { $listener.Start() } finally { $listener.Stop() }
if (-not (Test-Path -LiteralPath $data)) {
    & (Join-Path $MariaDbBin 'mariadb-install-db.exe') "--datadir=$data" --port=43179 --silent
    if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize isolated test database' }
}
$serverArguments = @('--no-defaults', ('--datadir="' + $data + '"'), '--port=43179', '--bind-address=127.0.0.1', '--skip-name-resolve', '--event-scheduler=OFF', '--pid-file=test-server.pid', '--log-error=test-server.log')
$server = Start-Process -FilePath (Join-Path $MariaDbBin 'mariadbd.exe') -ArgumentList $serverArguments -WindowStyle Hidden -PassThru
Push-Location $root
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($server.HasExited) { throw 'Isolated MariaDB exited. Inspect build/sql-transfer-test-data/test-server.log' }
        $client = New-Object Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1',43179); $ready=$true; break } catch { Start-Sleep -Milliseconds 250 } finally { $client.Dispose() }
    }
    if (-not $ready) { throw 'Isolated MariaDB startup timed out' }
    if (-not $SkipGeneralSuite) {
        & (Join-Path $root 'test.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Base test suite failed' }
    }
    $classpath = "$root/build/classes;$root/build/test-classes;$root/build/lib/*"
    & java '-Dnexodb.disableDraftPersistence=true' '-Dnexodb.test.transfer=true' "-Dnexodb.test.mariaDbBin=$MariaDbBin" -cp $classpath com.nexodb.db.SqlTransferIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'SQL backup/import round trip failed' }
    & java '-Dnexodb.disableDraftPersistence=true' '-Dnexodb.test.transfer=true' -cp $classpath com.nexodb.ui.SqlTransferWindowTest
    if ($LASTEXITCODE -ne 0) { throw 'SQL transfer UI test failed' }
} finally {
    try {
        if (-not $server.HasExited) {
            & (Join-Path $MariaDbBin 'mariadb-admin.exe') --no-defaults --skip-ssl --host=127.0.0.1 --port=43179 --user=root shutdown
            if (-not $server.WaitForExit(5000)) { $server.Kill() }
        }
    } finally { Pop-Location; $server.Dispose() }
}
