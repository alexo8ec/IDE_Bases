param([string]$ManifestUrl = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim()
$image = Join-Path $root "dist\$version\NexoDB Studio"
$verification = [IO.Path]::GetFullPath((Join-Path $root 'build\runtime-verification'))
if (-not $verification.StartsWith([IO.Path]::GetFullPath((Join-Path $root 'build')) + '\')) { throw 'Unsafe verification path' }
if (Test-Path -LiteralPath $verification) { Remove-Item -LiteralPath $verification -Recurse -Force }
New-Item -ItemType Directory -Path $verification -Force | Out-Null
$jdk = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
$runtime = Join-Path $verification 'runtime'
Copy-Item -LiteralPath (Join-Path $image 'runtime') -Destination $runtime -Recurse
# jpackage omits the java command. Add the matching JDK command only to this
# disposable test copy; all modules and JVM libraries are the shipped runtime.
Copy-Item -LiteralPath (Join-Path $jdk 'bin\java.exe') -Destination (Join-Path $runtime 'bin\java.exe')
$modules = Get-Content -LiteralPath (Join-Path $runtime 'release') -Raw
if ($modules -notmatch 'jdk.crypto.ec') { throw 'Packaged runtime is missing jdk.crypto.ec' }
& javac -encoding UTF-8 -d $verification (Join-Path $PSScriptRoot 'RuntimeTlsCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile runtime verification' }
$testArgs = @('-cp', $verification, 'RuntimeTlsCheck')
if ($ManifestUrl) { $testArgs += $ManifestUrl }
& (Join-Path $runtime 'bin\java.exe') @testArgs
if ($LASTEXITCODE -ne 0) { throw 'Packaged runtime TLS verification failed' }
$exe = Join-Path $root "dist\installer\NexoDB Studio-$version.exe"
if ((Get-Item -LiteralPath $exe).VersionInfo.FileVersion -ne $version) { throw 'Incorrect installer version' }
Write-Host "Windows $version installer and runtime verified."
