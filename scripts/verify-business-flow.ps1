#Requires -Version 7.4
param(
 [string]$BaseUrl='http://127.0.0.1:8080',
 [string]$AdminUsername='admin',
 [string]$PasswordFile,
 [string]$OutputDirectory,
 [string]$CleanupResultFile,
 [string]$JarFile,
 [string]$ReleaseId
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'release-common.ps1')
if(-not $PasswordFile){$PasswordFile=Join-Path $root 'private/demo-accounts.txt'}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $root 'artifacts/validation'}
$previousPassword=$env:RECONIS_VERIFY_PASSWORD
$previousJar=$env:RECONIS_VERIFY_JAR
$previousJarHash=$env:RECONIS_VERIFY_JAR_SHA256
try {
 if(-not $CleanupResultFile){
  $release=Resolve-ReconisServerJar -Root $root -JarFile $JarFile -ReleaseId $ReleaseId
  $env:RECONIS_VERIFY_JAR=$release.JarPath
  $env:RECONIS_VERIFY_JAR_SHA256=$release.Sha256
 }
 if(-not $env:RECONIS_VERIFY_PASSWORD){
  $text=Get-Content -LiteralPath $PasswordFile -Raw -Encoding UTF8
  $match=[regex]::Match($text,'统一初始密码[：:]\s*([^\r\n]+)')
  if(-not $match.Success){throw '未找到本机 DEMO 初始密码。可通过 RECONIS_VERIFY_PASSWORD 环境变量提供现有管理员密码。'}
  $env:RECONIS_VERIFY_PASSWORD=$match.Groups[1].Value.Trim()
 }
 $nodeArgs=@((Join-Path $PSScriptRoot 'verify-business-flow.mjs'),'--base-url',$BaseUrl,'--admin',$AdminUsername,'--output',$OutputDirectory)
 if($CleanupResultFile){$nodeArgs+=@('--cleanup-results',(Resolve-Path -LiteralPath $CleanupResultFile).Path)}
 & node @nodeArgs
 if($LASTEXITCODE -ne 0){throw "业务流程验证失败，退出码 $LASTEXITCODE；详情见验证结果文件。"}
}finally{
 $env:RECONIS_VERIFY_PASSWORD=$previousPassword
 $env:RECONIS_VERIFY_JAR=$previousJar
 $env:RECONIS_VERIFY_JAR_SHA256=$previousJarHash
}
