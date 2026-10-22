#Requires -Version 7.4
param(
 [string]$MediaRoot = "$PSScriptRoot\..\.tools\sqlserver",
 [string]$SqlAdminAccount = [Security.Principal.WindowsIdentity]::GetCurrent().Name
)
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw '请用 Windows 管理员 PowerShell 运行本脚本。'}
if($SqlAdminAccount -notmatch '^[^"\r\n]+\\[^"\r\n]+$'){throw '数据库管理员须为有效的 Windows 域或计算机账号。'}
if(Get-Service -Name 'MSSQL$RECONIS' -ErrorAction SilentlyContinue){throw 'RECONIS 实例已经存在，请使用 initialize.ps1 或 migrate.ps1；不重复安装。'}
$root=[IO.Path]::GetFullPath($MediaRoot)
New-Item -ItemType Directory -Path $root -Force | Out-Null
$iso=Get-ChildItem -LiteralPath $root -Filter '*.iso' | Select-Object -First 1
if(-not $iso){
 Write-Host '下载 SQL Server 2022 Developer 官方ISO。'
 $image=Join-Path $root 'SQLServer2022-x64-ENU-Dev.iso'
 $partial="$image.partial"
 Invoke-WebRequest 'https://download.microsoft.com/download/3/8/d/38de7036-2433-4207-8eae-06e247e17b25/SQLServer2022-x64-ENU-Dev.iso' -OutFile $partial -TimeoutSec 1800
 if((Get-Item -LiteralPath $partial).Length -ne 1163053056L){throw '安装介质下载不完整，请重新下载。'}
 Move-Item -LiteralPath $partial -Destination $image
 $iso=Get-Item -LiteralPath $image
}
$mounted=Mount-DiskImage -ImagePath $iso.FullName -PassThru
try{
 $volume=$mounted | Get-Volume
 $setup="$($volume.DriveLetter):\setup.exe"
 $signature=Get-AuthenticodeSignature -LiteralPath $setup
 if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*'){throw '安装程序不是有效的 Microsoft 签名。'}
 $arguments=@('/Q','/ACTION=Install','/FEATURES=SQLENGINE,FULLTEXT','/INSTANCENAME=RECONIS','/SQLSVCSTARTUPTYPE=Automatic',"/SQLSYSADMINACCOUNTS=`"$SqlAdminAccount`"",'/TCPENABLED=1','/NPENABLED=0','/IACCEPTSQLSERVERLICENSETERMS','/UPDATEENABLED=False','/SQLCOLLATION=Chinese_PRC_CI_AS','/SQLTEMPDBFILECOUNT=2','/SQLMAXMEMORY=2048')
 $process=Start-Process -FilePath $setup -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
 if($process.ExitCode -notin 0,3010){throw "SQL Server 安装未完成，退出码 $($process.ExitCode)。查看 C:\Program Files\Microsoft SQL Server\160\Setup Bootstrap\Log。"}
 $tcp='HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer\SuperSocketNetLib\Tcp'
 $localAddresses=@(Get-ChildItem -LiteralPath $tcp | Where-Object PSChildName -Match '^IP\d+$' | Where-Object {(Get-ItemProperty -LiteralPath $_.PSPath).IpAddress -eq '127.0.0.1'})
 if($localAddresses.Count -ne 1){Stop-Service -Name 'MSSQL$RECONIS';throw '未找到唯一的本机 IPv4 配置，数据库服务已停止，请检查 TCP/IP 配置。'}
 Set-ItemProperty -LiteralPath $tcp -Name ListenOnAllIPs -Value 0
 Get-ChildItem -LiteralPath $tcp | Where-Object PSChildName -Match '^IP\d+$' | ForEach-Object { $ip=Get-ItemProperty -LiteralPath $_.PSPath; Set-ItemProperty -LiteralPath $_.PSPath -Name Enabled -Value ([int]($ip.IpAddress -eq '127.0.0.1')); Set-ItemProperty -LiteralPath $_.PSPath -Name TcpDynamicPorts -Value ''; Set-ItemProperty -LiteralPath $_.PSPath -Name TcpPort -Value '1433' }
 Set-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer' -Name LoginMode -Value 2
 # Microsoft documents this flag for SESSION_CONTEXT with pooled connections and parallel plans.
 $parameters='HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer\Parameters'
 $startupArguments=(Get-ItemProperty -LiteralPath $parameters).PSObject.Properties | Where-Object Name -match '^SQLArg\d+$'
 if(-not($startupArguments | Where-Object Value -eq '-T11042')){
  $index=1+[int](($startupArguments | ForEach-Object {[int]($_.Name -replace '^SQLArg','')} | Measure-Object -Maximum).Maximum)
  New-ItemProperty -LiteralPath $parameters -Name "SQLArg$index" -Value '-T11042' -PropertyType String -Force | Out-Null
 }
 Restart-Service -Name 'MSSQL$RECONIS'
 Write-Host 'SQL Server RECONIS 和全文组件已安装，仅启用本机127.0.0.1端口1433。接着运行 scripts\initialize.ps1。'
}finally{Dismount-DiskImage -ImagePath $iso.FullName | Out-Null}
