#Requires -Version 5.1
[CmdletBinding(DefaultParameterSetName='Current')]
param(
    [Parameter(Mandatory=$true,ParameterSetName='Select')][string]$ReleaseId,
    [Parameter(Mandatory=$true,ParameterSetName='Rollback')][switch]$Rollback,
    [Parameter(Mandatory=$true,ParameterSetName='List')][switch]$List
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'release-common.ps1')
if($List){
    $releaseRoot=Join-Path $root 'artifacts\releases'
    if(Test-Path -LiteralPath $releaseRoot){
        Get-ChildItem -LiteralPath $releaseRoot -Directory | Sort-Object Name -Descending | ForEach-Object {
            if(Test-Path -LiteralPath (Join-Path $_.FullName 'manifest.json')){
                $release=Get-ReconisRelease -Root $root -ReleaseId $_.Name
                [pscustomobject]@{ReleaseId=$release.ReleaseId;Version=$release.Version;CreatedUtc=$release.Manifest.createdUtc;Sha256=$release.Sha256}
            }
        }
    }
    return
}
if($Rollback){$release=Set-ReconisCurrentRelease -Root $root -Rollback}
elseif($ReleaseId){$release=Set-ReconisCurrentRelease -Root $root -ReleaseId $ReleaseId}
else{$release=Read-ReconisReleasePointer -Root $root}
if($null -eq $release){throw '尚未选择统一运行版本，请先运行 scripts\build.ps1。'}
Write-Host "统一运行版本：$($release.ReleaseId)"
Write-Host "版本：$($release.Version)；SHA256：$($release.Sha256)"
Write-Host '当前进程不会被中断；下一次启动使用该版本。显式 -JarFile、-ReleaseId 或 RECONIS_JAR_FILE 仍优先于统一选择。'
$release | Select-Object ReleaseId,Version,JarPath,Sha256
