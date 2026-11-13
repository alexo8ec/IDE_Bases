$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$driver = Join-Path $root 'build/h2-test.jar'
$expected = '29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0'
New-Item -ItemType Directory -Path (Join-Path $root 'build') -Force | Out-Null
if (-not (Test-Path -LiteralPath $driver)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.jar' -OutFile $driver
}
if ((Get-FileHash -LiteralPath $driver -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'H2 test driver SHA-256 mismatch' }
Push-Location $root
try { & (Join-Path $root 'test.ps1') } finally { Pop-Location }
