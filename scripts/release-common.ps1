#Requires -Version 5.1
# Shared, read-only release resolution. Dot-sourcing this file does not select a release.

function Get-ReconisFileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    # Start-Process can inherit PowerShell 7's module paths into Windows PowerShell 5.1.
    # Use .NET directly so startup does not depend on Get-FileHash module autoloading.
    $stream=$null
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try{
        $stream=[IO.File]::OpenRead($Path)
        return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','')
    }finally{
        if($null -ne $stream){$stream.Dispose()}
        $algorithm.Dispose()
    }
}

function Get-ReconisRelease {
    param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$ReleaseId)
    if($ReleaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'){throw 'Invalid release ID.'}
    $directory=Join-Path $Root "artifacts\releases\$ReleaseId"
    $manifestPath=Join-Path $directory 'manifest.json'
    if(-not(Test-Path -LiteralPath $manifestPath -PathType Leaf)){throw "Release manifest not found: $ReleaseId"}
    $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if($manifest.schemaVersion -ne 1 -or $manifest.releaseId -cne $ReleaseId -or
        $manifest.jarFile -cne 'reconis-server.jar' -or $manifest.jarSha256 -notmatch '^[A-Fa-f0-9]{64}$'){
        throw "Invalid release manifest: $ReleaseId"
    }
    $jar=Join-Path $directory $manifest.jarFile
    if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){throw "Release JAR not found: $ReleaseId"}
    $hash=Get-ReconisFileSha256 -Path $jar
    if($hash -ine $manifest.jarSha256){throw "Release JAR SHA256 mismatch: $ReleaseId"}
    [pscustomobject]@{Source='release';ReleaseId=$ReleaseId;Version=$manifest.version;JarPath=$jar;Sha256=$hash;Manifest=$manifest}
}

function Read-ReconisReleasePointer {
    param([Parameter(Mandatory=$true)][string]$Root,[string]$Name='current')
    if($Name -notin @('current','previous')){throw 'Invalid release pointer name.'}
    $path=Join-Path $Root "artifacts\releases\$Name.json"
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){return $null}
    $pointer=Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if($pointer.schemaVersion -ne 1 -or $pointer.jarSha256 -notmatch '^[A-Fa-f0-9]{64}$'){throw "Invalid $Name release pointer."}
    $release=Get-ReconisRelease -Root $Root -ReleaseId $pointer.releaseId
    if($release.Sha256 -ine $pointer.jarSha256){throw "The $Name pointer does not match its release manifest."}
    return $release
}

function Resolve-ReconisServerJar {
    param([Parameter(Mandatory=$true)][string]$Root,[string]$JarFile,[string]$ReleaseId,[switch]$IgnoreEnvironmentOverride)
    if($JarFile -and $ReleaseId){throw 'Specify either JarFile or ReleaseId, not both.'}
    if($ReleaseId){return Get-ReconisRelease -Root $Root -ReleaseId $ReleaseId}
    if(-not $JarFile -and -not $IgnoreEnvironmentOverride){$JarFile=$env:RECONIS_JAR_FILE}
    if(-not [string]::IsNullOrWhiteSpace($JarFile)){
        $jar=if([IO.Path]::IsPathRooted($JarFile)){$JarFile}else{Join-Path $Root $JarFile}
        if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){throw 'RECONIS_JAR_FILE or -JarFile does not point to an existing file.'}
        $jar=(Resolve-Path -LiteralPath $jar).Path
        return [pscustomobject]@{Source='override';ReleaseId=$null;Version=$null;JarPath=$jar;Sha256=(Get-ReconisFileSha256 -Path $jar);Manifest=$null}
    }
    $current=Read-ReconisReleasePointer -Root $Root
    if($null -ne $current){return $current}
    $legacy=Join-Path $Root 'backend\target\reconis-server-1.0.0.jar'
    if(-not(Test-Path -LiteralPath $legacy -PathType Leaf)){throw 'No selected release or legacy JAR found. Run scripts/build.ps1 first.'}
    [pscustomobject]@{Source='legacy';ReleaseId=$null;Version=$null;JarPath=$legacy;Sha256=(Get-ReconisFileSha256 -Path $legacy);Manifest=$null}
}

function Write-ReconisJsonAtomic {
    param([Parameter(Mandatory=$true)][string]$Path,[Parameter(Mandatory=$true)]$Value)
    $temporary="$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    $backup="$temporary.previous"
    try{
        [IO.File]::WriteAllText($temporary,($Value | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($true))
        # Windows PowerShell 5.1 binds a null string argument as an empty path; use a real temporary backup.
        if([IO.File]::Exists($Path)){[IO.File]::Replace($temporary,$Path,$backup)}else{[IO.File]::Move($temporary,$Path)}
    }finally{
        if([IO.File]::Exists($temporary)){[IO.File]::Delete($temporary)}
        if([IO.File]::Exists($backup)){[IO.File]::Delete($backup)}
    }
}

function Set-ReconisCurrentRelease {
    param([Parameter(Mandatory=$true)][string]$Root,[string]$ReleaseId,[switch]$Rollback)
    if($Rollback -and $ReleaseId){throw 'Specify either ReleaseId or Rollback, not both.'}
    $releaseRoot=Join-Path $Root 'artifacts\releases'
    New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
    $lock=$null
    try{
        try{$lock=[IO.File]::Open((Join-Path $releaseRoot '.selection.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
        catch{throw 'Another release selection is in progress. Retry after it completes.'}
        if($Rollback){
            $release=Read-ReconisReleasePointer -Root $Root -Name previous
            if($null -eq $release){throw 'No previous release is available for rollback.'}
        }else{
            if([string]::IsNullOrWhiteSpace($ReleaseId)){throw 'ReleaseId is required.'}
            $release=Get-ReconisRelease -Root $Root -ReleaseId $ReleaseId
        }
        $currentPath=Join-Path $releaseRoot 'current.json'
        if(Test-Path -LiteralPath $currentPath -PathType Leaf){
            # Keep the previous pointer, not a copy of a running JAR. Existing processes are untouched.
            $old=Get-Content -LiteralPath $currentPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if($old.releaseId -ceq $release.ReleaseId -and $old.jarSha256 -ieq $release.Sha256){return $release}
            if(-not $Rollback){[void](Read-ReconisReleasePointer -Root $Root)}
            Write-ReconisJsonAtomic -Path (Join-Path $releaseRoot 'previous.json') -Value $old
        }
        $pointer=[ordered]@{schemaVersion=1;releaseId=$release.ReleaseId;jarSha256=$release.Sha256;selectedUtc=[DateTime]::UtcNow.ToString('o')}
        Write-ReconisJsonAtomic -Path $currentPath -Value $pointer
        return $release
    }finally{if($null -ne $lock){$lock.Dispose()}}
}
