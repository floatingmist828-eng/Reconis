#Requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-common.ps1')
$fixtureRoot=Join-Path (Split-Path -Parent $PSScriptRoot) ("artifacts\validation\release-selector-"+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$checks=0
function Assert-ReleaseCondition([bool]$Condition,[string]$Message){
    if(-not $Condition){throw "Release selector check failed: $Message"}
    $script:checks++
}
function Assert-ReleaseFailure([scriptblock]$Action,[string]$Message){
    $failed=$false
    try{& $Action | Out-Null}catch{$failed=$true}
    Assert-ReleaseCondition $failed $Message
}
function New-FixtureRelease([string]$Id){
    $directory=Join-Path $fixtureRoot "artifacts\releases\$Id"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $jar=Join-Path $directory 'reconis-server.jar'
    [IO.File]::WriteAllText($jar,"fixture-$Id")
    Write-ReconisJsonAtomic -Path (Join-Path $directory 'manifest.json') -Value @{
        schemaVersion=1;releaseId=$Id;version='test';jarFile='reconis-server.jar';jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    }
}
$previousOverride=$env:RECONIS_JAR_FILE
try{
    $env:RECONIS_JAR_FILE=$null
    New-FixtureRelease 'first'
    New-FixtureRelease 'second'
    Assert-ReleaseFailure {Resolve-ReconisServerJar -Root $fixtureRoot} 'Missing selection must not pick an arbitrary release.'
    [void](Set-ReconisCurrentRelease -Root $fixtureRoot -ReleaseId first)
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot).ReleaseId -ceq 'first') 'Selected release resolution.'
    [void](Set-ReconisCurrentRelease -Root $fixtureRoot -ReleaseId second)
    Assert-ReleaseCondition ((Read-ReconisReleasePointer -Root $fixtureRoot -Name previous).ReleaseId -ceq 'first') 'Previous release preserved.'
    [void](Set-ReconisCurrentRelease -Root $fixtureRoot -ReleaseId second)
    Assert-ReleaseCondition ((Read-ReconisReleasePointer -Root $fixtureRoot -Name previous).ReleaseId -ceq 'first') 'Selecting the same release must preserve rollback.'
    [void](Set-ReconisCurrentRelease -Root $fixtureRoot -Rollback)
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot).ReleaseId -ceq 'first') 'Rollback selects previous release.'
    Assert-ReleaseCondition ((Read-ReconisReleasePointer -Root $fixtureRoot -Name previous).ReleaseId -ceq 'second') 'Rollback is reversible.'
    $env:RECONIS_JAR_FILE=Join-Path $fixtureRoot 'artifacts\releases\second\reconis-server.jar'
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot).Source -ceq 'override') 'Existing environment overrides are preserved.'
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot -ReleaseId first).ReleaseId -ceq 'first') 'Explicit release takes priority over environment.'
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot -IgnoreEnvironmentOverride).ReleaseId -ceq 'first') 'Environment override can be ignored explicitly.'
    Assert-ReleaseFailure {Resolve-ReconisServerJar -Root $fixtureRoot -JarFile $env:RECONIS_JAR_FILE -ReleaseId first} 'Conflicting explicit options are rejected.'
    Assert-ReleaseFailure {Get-ReconisRelease -Root $fixtureRoot -ReleaseId '..\first'} 'Path traversal release IDs are rejected.'
    $env:RECONIS_JAR_FILE=$null
    $firstJar=Join-Path $fixtureRoot 'artifacts\releases\first\reconis-server.jar'
    [IO.File]::AppendAllText($firstJar,'changed')
    Assert-ReleaseFailure {Resolve-ReconisServerJar -Root $fixtureRoot} 'Changed JAR hash must fail, with no silent fallback.'
    [void](Set-ReconisCurrentRelease -Root $fixtureRoot -Rollback)
    Assert-ReleaseCondition ((Resolve-ReconisServerJar -Root $fixtureRoot).ReleaseId -ceq 'second') 'A valid prior release can recover from a corrupted current JAR.'
    $pointerPath=Join-Path $fixtureRoot 'artifacts\releases\current.json'
    $pointer=Get-Content -LiteralPath $pointerPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $pointer.jarSha256=('0'*64)
    Write-ReconisJsonAtomic -Path $pointerPath -Value $pointer
    Assert-ReleaseFailure {Resolve-ReconisServerJar -Root $fixtureRoot} 'Pointer hash must agree with the manifest.'
    $summary=[ordered]@{passed=$checks;powerShellVersion=$PSVersionTable.PSVersion.ToString();fixtureRoot=$fixtureRoot;productionFilesModified=$false}
    Write-ReconisJsonAtomic -Path (Join-Path $fixtureRoot 'result.json') -Value $summary
    [pscustomobject]$summary
}finally{$env:RECONIS_JAR_FILE=$previousOverride}
