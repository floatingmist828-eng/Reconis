#Requires -Version 5.1
param([string]$PackagePath="$PSScriptRoot\..\.tools\sqlserver\SQLServer2022-KB5126934-x64.exe")
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw '更新 SQL Server 需要 Windows 管理员权限。'}
if(-not(Get-Service -Name 'MSSQL$RECONIS' -ErrorAction SilentlyContinue)){throw 'RECONIS 实例尚未安装。'}
$package=(Resolve-Path -LiteralPath $PackagePath).Path
$expectedHash='8F1A7F0AC5F47FF98F5825687C8CCD897563AA835915F2271F908273E962E7E2'
if((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash -ne $expectedHash){throw 'KB5126934 文件散列与微软公布值不一致。'}
$signature=Get-AuthenticodeSignature -LiteralPath $package
if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*'){throw 'SQL Server 更新程序没有有效的 Microsoft 签名。'}
# SQL Setup suppresses automatic Windows restart and reports 3010 when one is required.
# It does not accept the generic Windows Installer /norestart switch.
$process=Start-Process -FilePath $package -ArgumentList @('/quiet','/action=Patch','/instancename=RECONIS','/IAcceptSQLServerLicenseTerms') -WindowStyle Hidden -Wait -PassThru
if($process.ExitCode -notin 0,3010){throw "SQL Server 更新失败，退出码 $($process.ExitCode)，请检查 SQL Server Setup Bootstrap 日志。"}
# SESSION_CONTEXT is used for audit and tenant views. Microsoft documents trace flag 11042
# for its parallel-plan/connection-reuse issue: https://learn.microsoft.com/sql/t-sql/functions/session-context-transact-sql
$parameters='HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer\Parameters'
$arguments=(Get-ItemProperty -LiteralPath $parameters).PSObject.Properties | Where-Object Name -match '^SQLArg\d+$'
if(-not($arguments | Where-Object Value -eq '-T11042')){
 $index=1+[int](($arguments | ForEach-Object {[int]($_.Name -replace '^SQLArg','')} | Measure-Object -Maximum).Maximum)
 New-ItemProperty -LiteralPath $parameters -Name "SQLArg$index" -Value '-T11042' -PropertyType String -Force | Out-Null
}
Restart-Service -Name 'MSSQL$RECONIS'
if($process.ExitCode -eq 3010){Write-Warning '更新程序报告需要重启 Windows；尚未自动重启。'}
[pscustomobject]@{Instance='RECONIS';TargetVersion='16.0.4305.5';RestartRequired=($process.ExitCode -eq 3010)}
