#Requires -Version 7.4
param([string]$MediaRoot = "$PSScriptRoot\..\.tools\sqlserver")
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw '请用 Windows 管理员 PowerShell 运行本脚本。'}
$root=[IO.Path]::GetFullPath($MediaRoot)
New-Item -ItemType Directory -Path $root -Force | Out-Null
$iso=Get-ChildItem -LiteralPath $root -Filter '*.iso' | Select-Object -First 1
if(-not $iso){
 Write-Host '下载 SQL Server 2022 Developer 官方ISO。'
 $image=Join-Path $root 'SQLServer2022-x64-ENU-Dev.iso'
 Invoke-WebRequest 'https://download.microsoft.com/download/3/8/d/38de7036-2433-4207-8eae-06e247e17b25/SQLServer2022-x64-ENU-Dev.iso' -OutFile $image
 $iso=Get-Item -LiteralPath $image
}
$mounted=Mount-DiskImage -ImagePath $iso.FullName -PassThru
try{
 $volume=$mounted | Get-Volume
 $setup="$($volume.DriveLetter):\setup.exe"
 $signature=Get-AuthenticodeSignature -LiteralPath $setup
 if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*'){throw '安装程序不是有效的 Microsoft 签名。'}
 $current=[Security.Principal.WindowsIdentity]::GetCurrent().Name
 $arguments=@('/Q','/ACTION=Install','/FEATURES=SQLENGINE,FULLTEXT','/INSTANCENAME=RECONIS','/SQLSVCSTARTUPTYPE=Automatic',"/SQLSYSADMINACCOUNTS=`"$current`"",'/TCPENABLED=1','/NPENABLED=0','/IACCEPTSQLSERVERLICENSETERMS','/UPDATEENABLED=False','/SQLCOLLATION=Chinese_PRC_CI_AS')
 $process=Start-Process -FilePath $setup -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
 if($process.ExitCode -notin 0,3010){throw "SQL Server 安装未完成，退出码 $($process.ExitCode)。查看 C:\Program Files\Microsoft SQL Server\160\Setup Bootstrap\Log。"}
 $tcp='HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer\SuperSocketNetLib\Tcp'
 Set-ItemProperty -LiteralPath $tcp -Name ListenOnAllIPs -Value 0
 Get-ChildItem -LiteralPath $tcp | Where-Object PSChildName -Match '^IP\d+$' | ForEach-Object { $ip=Get-ItemProperty -LiteralPath $_.PSPath; Set-ItemProperty -LiteralPath $_.PSPath -Name Enabled -Value ([int]($ip.IpAddress -eq '127.0.0.1')); Set-ItemProperty -LiteralPath $_.PSPath -Name TcpDynamicPorts -Value ''; Set-ItemProperty -LiteralPath $_.PSPath -Name TcpPort -Value '1433' }
 Set-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL16.RECONIS\MSSQLServer' -Name LoginMode -Value 2
 Restart-Service -Name 'MSSQL$RECONIS'
 Write-Host 'SQL Server RECONIS 和全文组件已安装，仅启用本机127.0.0.1端口1433。接着运行 scripts\initialize.ps1。'
}finally{Dismount-DiskImage -ImagePath $iso.FullName | Out-Null}
