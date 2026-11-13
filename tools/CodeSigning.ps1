# Public release signing uses an existing CA-issued certificate. No private keys are exported.
function Get-NexoSigner {
    param([string]$Thumbprint)
    if ($Thumbprint -notmatch '^[A-Fa-f0-9]{40}$') { throw 'Indica la huella de un certificado de firma de codigo instalado (40 digitos hexadecimales).' }
    $certificate = Get-Item -LiteralPath "Cert:\CurrentUser\My\$Thumbprint" -ErrorAction Stop
    if (-not $certificate.HasPrivateKey) { throw 'El certificado no tiene una clave privada accesible.' }
    if ($certificate.NotAfter -le (Get-Date) -or $certificate.NotBefore -gt (Get-Date)) { throw 'El certificado no esta vigente.' }
    if ($certificate.Subject -eq $certificate.Issuer) { throw 'Una firma autofirmada no sirve para una publicacion publica de confianza.' }
    if ('1.3.6.1.5.5.7.3.3' -notin @($certificate.EnhancedKeyUsageList | ForEach-Object { $_.ObjectId.Value })) { throw 'El certificado no permite firma de codigo.' }
    $chain = New-Object Security.Cryptography.X509Certificates.X509Chain
    try { if (-not $chain.Build($certificate)) { throw 'La cadena de confianza del certificado no se pudo validar.' } } finally { $chain.Dispose() }
    $tool = Get-Command signtool.exe -ErrorAction SilentlyContinue
    $toolPath = if ($tool) { $tool.Source } else { '' }
    if (-not $toolPath) {
        $sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
        $candidate = Get-ChildItem -LiteralPath $sdkRoot -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Directory.Name -eq 'x64' } | Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { $toolPath = $candidate.FullName }
    }
    if (-not $toolPath) { throw 'Falta SignTool del Windows SDK. Instala sus herramientas de firma antes de publicar.' }
    return [pscustomobject]@{ Certificate = $certificate; Tool = $toolPath }
}

function Invoke-NexoSignFile {
    param([Parameter(Mandatory)]$Signer, [Parameter(Mandatory)][string]$Path)
    $absolute = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    & $Signer.Tool sign /fd SHA256 /sha1 $Signer.Certificate.Thumbprint /tr https://timestamp.digicert.com /td SHA256 /d 'NexoDB Studio' $absolute
    if ($LASTEXITCODE -ne 0) { throw "No se pudo firmar: $absolute" }
    & $Signer.Tool verify /pa /all /tw $absolute
    if ($LASTEXITCODE -ne 0) { throw "No se pudo verificar la firma: $absolute" }
    $signature = Get-AuthenticodeSignature -LiteralPath $absolute
    if ($signature.Status -ne 'Valid' -or -not $signature.TimeStamperCertificate) { throw 'La firma no es valida o no tiene sello de tiempo verificable.' }
    if ($signature.SignerCertificate.Thumbprint -ne $Signer.Certificate.Thumbprint) { throw 'La identidad de firma no coincide con la configurada.' }
}
