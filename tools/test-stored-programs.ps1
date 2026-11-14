param([string]$MariaDbBin='C:/Program Files/MariaDB 12.3/bin',[switch]$WithUi)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$data=[IO.Path]::GetFullPath((Join-Path $root 'build/stored-program-test-data'))
if(-not $data.StartsWith((Join-Path $root 'build')+[IO.Path]::DirectorySeparatorChar)){throw 'Unsafe test data path'}
$listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,43189)
try{$listener.Start()}finally{$listener.Stop()}
if(-not(Test-Path -LiteralPath $data)){
    & (Join-Path $MariaDbBin 'mariadb-install-db.exe') "--datadir=$data" --port=43189 --silent
    if($LASTEXITCODE -ne 0){throw 'Cannot initialize dedicated fixture'}
}
$serverArgs=@('--no-defaults',('--datadir="'+$data+'"'),'--port=43189','--bind-address=127.0.0.1','--skip-name-resolve','--event-scheduler=OFF','--pid-file=stored-test.pid','--log-error=stored-test.log')
$server=Start-Process -FilePath (Join-Path $MariaDbBin 'mariadbd.exe') -ArgumentList $serverArgs -WindowStyle Hidden -PassThru
Push-Location $root
try{
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        $previousErrorAction=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try{& (Join-Path $MariaDbBin 'mariadb-admin.exe') --no-defaults --skip-ssl --host=127.0.0.1 --port=43189 --user=root ping 2>$null | Out-Null}finally{$ErrorActionPreference=$previousErrorAction}
        if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 250
    }
    if(-not $ready){throw 'Dedicated fixture did not start'}
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dnexodb.disableDraftPersistence=true' '-Dnexodb.test.storedPrograms=true' -cp 'build/test-classes;build/classes;build/lib/*' com.nexodb.db.StoredProgramIntegrationTest
    if($LASTEXITCODE -ne 0){throw 'Stored-program integration test failed'}
    if($WithUi){
        & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dnexodb.disableDraftPersistence=true' '-Dnexodb.test.storedPrograms=true' -cp 'build/test-classes;build/classes;build/lib/*' com.nexodb.ui.StoredProgramWindowTest
        if($LASTEXITCODE -ne 0){throw 'Stored-program window test failed'}
    }
}finally{
    try{if(-not $server.HasExited){& (Join-Path $MariaDbBin 'mariadb-admin.exe') --no-defaults --skip-ssl --host=127.0.0.1 --port=43189 --user=root shutdown;if(-not $server.WaitForExit(5000)){$server.Kill()}}}finally{Pop-Location;$server.Dispose()}
}
