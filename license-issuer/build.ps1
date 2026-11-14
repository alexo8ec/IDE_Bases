param([switch]$InitializeAuthority, [switch]$Package)
$ErrorActionPreference='Stop'
$issuerRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$root=Split-Path -Parent $issuerRoot
$build=Join-Path $issuerRoot 'build'
$classes=Join-Path $build 'classes'
$public=Join-Path $root 'resources/com/nexodb/licensing/public-key.der'
$private=Join-Path $root '.signing'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $issuerRoot 'src') -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
$sources+=Join-Path $root 'src/com/nexodb/licensing/LicenseCodec.java'
& javac --release 17 -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Issuer compilation failed'}
if($InitializeAuthority -and -not (Test-Path -LiteralPath $public) -and -not (Test-Path -LiteralPath (Join-Path $private 'issuer.nxvault'))){
    & java -cp $classes com.nexodb.issuer.IssuerBootstrap $private $public
    if($LASTEXITCODE -ne 0){throw 'Authority initialization failed'}
}
if($InitializeAuthority -and (Test-Path -LiteralPath (Join-Path $private 'issuer.nxvault'))){
    # The private authority and initial recovery password are local administrative files only.
    if($env:OS -eq 'Windows_NT'){
        $identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
        foreach($item in @(Get-Item -LiteralPath $private) + @(Get-ChildItem -LiteralPath $private -File)){
            $acl=Get-Acl -LiteralPath $item.FullName
            $acl.SetAccessRuleProtection($true,$false)
            foreach($rule in @($acl.Access)){$acl.RemoveAccessRuleSpecific($rule)}
            $inherit=if($item.PSIsContainer){[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'}else{[Security.AccessControl.InheritanceFlags]::None}
            $rule=New-Object Security.AccessControl.FileSystemAccessRule($identity,[Security.AccessControl.FileSystemRights]::FullControl,$inherit,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
            $acl.AddAccessRule($rule);Set-Acl -LiteralPath $item.FullName -AclObject $acl
        }
    }
}
if(-not (Test-Path -LiteralPath $public)){throw 'Missing public license authority. Initialize once with -InitializeAuthority.'}
$resources=Join-Path $classes 'com/nexodb/licensing'
New-Item -ItemType Directory -Path $resources -Force | Out-Null
Copy-Item -LiteralPath $public -Destination (Join-Path $resources 'public-key.der') -Force
$issuerInput=Join-Path $build 'input'
New-Item -ItemType Directory -Path $issuerInput -Force | Out-Null
$jar=Join-Path $issuerInput 'nexodb-license-issuer.jar'
& jar --create --file $jar --main-class com.nexodb.issuer.IssuerApp -C $classes .
if($LASTEXITCODE -ne 0){throw 'Cannot build issuer JAR'}
if($Package){
    $destination=Join-Path $issuerRoot 'private-dist'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $image=Join-Path $destination 'NexoDB License Manager'
    $staging=Join-Path $build ('package-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    & jpackage --type app-image --name 'NexoDB License Manager' --app-version '1.0.1' --vendor 'NexoDB' --description 'Administrador privado de licencias NexoDB' --input $issuerInput --main-jar 'nexodb-license-issuer.jar' --main-class com.nexodb.issuer.IssuerApp --dest $staging --add-modules 'java.desktop,jdk.crypto.ec' --java-options '-Dfile.encoding=UTF-8'
    if($LASTEXITCODE -ne 0){throw 'Cannot package license manager'}
    $stagedImage=Join-Path $staging 'NexoDB License Manager'
    # Paths with spaces/accents belong in UTF-8 configuration, never in JVM argument parsing.
    $vaultPath=(Join-Path $private 'issuer.nxvault').Replace('\','/')
    [IO.File]::WriteAllText((Join-Path $stagedImage 'app/issuer.properties'),"vault.path=$vaultPath`n",[Text.UTF8Encoding]::new($false))
    $config=Get-Content -LiteralPath (Join-Path $stagedImage 'app/NexoDB License Manager.cfg')
    $options=@($config | Where-Object {$_ -like 'java-options=*'})
    if($options.Count -ne 2 -or $options -notcontains 'java-options=-Dfile.encoding=UTF-8' -or $options -notcontains 'java-options=-Djpackage.app-version=1.0.1'){throw 'Unexpected launcher arguments'}
    $zip=Join-Path $staging 'NexoDB-License-Manager-1.0.1-win64.zip'
    Compress-Archive -LiteralPath $stagedImage -DestinationPath $zip -CompressionLevel Optimal
    # Preserve the previous image until the replacement has been built successfully.
    if(Test-Path -LiteralPath $image){
        $resolved=[IO.Path]::GetFullPath($image)
        if($resolved -ne [IO.Path]::GetFullPath((Join-Path $issuerRoot 'private-dist/NexoDB License Manager'))){throw 'Unexpected previous image path'}
        Move-Item -LiteralPath $resolved -Destination (Join-Path $staging 'previous-image')
    }
    Move-Item -LiteralPath $stagedImage -Destination $image
    Move-Item -LiteralPath $zip -Destination (Join-Path $destination 'NexoDB-License-Manager-1.0.1-win64.zip') -Force
}
Write-Output "Generador compilado. La autoridad privada permanece en .signing y no se incluye en los paquetes."
