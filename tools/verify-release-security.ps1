param(
    [switch]$RequireWindowsSigning
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$version = (Get-Content -Raw -LiteralPath (Join-Path $root 'VERSION')).Trim()
$dist = Join-Path $root 'dist'
$installer = Join-Path $dist "installer\AdisDB-$version.exe"
$launcher = Join-Path $dist "$version\AdisDB\AdisDB.exe"

function Inspect-Authenticode([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Falta el artefacto: $Path" }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    $valid = $signature.Status -eq 'Valid'
    return [ordered]@{
        file = [IO.Path]::GetFileName($Path)
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        signed = $valid
        status = [string]$signature.Status
        signer = if ($valid) { $signature.SignerCertificate.Subject } else { '' }
        thumbprint = if ($valid) { $signature.SignerCertificate.Thumbprint } else { '' }
        timestamped = [bool]($valid -and $signature.TimeStamperCertificate)
    }
}

$installerSecurity = Inspect-Authenticode $installer
$launcherSecurity = Inspect-Authenticode $launcher
if ($RequireWindowsSigning -and (-not $installerSecurity.signed -or -not $launcherSecurity.signed -or
        -not $installerSecurity.timestamped -or -not $launcherSecurity.timestamped)) {
    throw 'La entrega pública exige que el instalador y el lanzador de Windows tengan firma Authenticode válida y sello de tiempo.'
}

$report = [ordered]@{
    product = 'AdisDB'
    version = $version
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    windows = [ordered]@{
        installer = $installerSecurity
        launcher = $launcherSecurity
        publicReleaseReady = [bool]($installerSecurity.signed -and $launcherSecurity.signed -and $installerSecurity.timestamped -and $launcherSecurity.timestamped)
    }
    macos = [ordered]@{
        status = 'verify-on-macos'
        requirement = 'Developer ID Application + notarization Accepted + stapled ticket + Gatekeeper accepted'
    }
    linux = [ordered]@{
        status = 'hash-verified'
        requirement = 'publish through a signed APT/RPM repository for publisher authentication'
    }
    android = [ordered]@{
        status = 'verified-by-tools/verify-android.py'
    }
}

$json = $report | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $dist 'security-manifest.json'), $json + "`n", [Text.UTF8Encoding]::new($false))
if ($report.windows.publicReleaseReady) {
    Write-Host 'Seguridad de Windows: firma del editor y sello de tiempo verificados.'
} else {
    Write-Warning 'Compilación interna sin firma pública de Windows. No debe anunciarse como instalador firmado.'
}
