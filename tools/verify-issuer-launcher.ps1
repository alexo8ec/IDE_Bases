param([string]$Image)
$ErrorActionPreference='Stop'
if(-not $Image){$Image=Join-Path (Split-Path -Parent $PSScriptRoot) 'license-issuer/private-dist/NexoDB License Manager'}
$executable=Join-Path ([IO.Path]::GetFullPath($Image)) 'NexoDB License Manager.exe'
if(-not (Test-Path -LiteralPath $executable)){throw 'Missing native issuer launcher'}
$resultName='nexodb-issuer-check-'+[Guid]::NewGuid().ToString('N')+'.txt'
$result=Join-Path $env:TEMP $resultName
$process=Start-Process -FilePath $executable -ArgumentList @('--check-startup',$resultName) -WorkingDirectory $env:TEMP -WindowStyle Hidden -PassThru
try{
    if(-not $process.WaitForExit(20000)){throw 'Native issuer startup check timed out'}
    if($process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $result)){throw "Native issuer startup failed (code $($process.ExitCode))"}
    if((Get-Content -LiteralPath $result -Raw) -notlike 'OK: native launcher*'){throw 'Invalid startup check result'}
    Write-Output (Get-Content -LiteralPath $result -Raw).Trim()
}finally{
    if(-not $process.HasExited){Stop-Process -Id $process.Id -Force}
    if(Test-Path -LiteralPath $result){Remove-Item -LiteralPath $result}
}
