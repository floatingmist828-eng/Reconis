#Requires -Version 7.4
param([ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',[string]$Server='localhost\RECONIS',[Parameter(Mandatory)][string]$BackupDirectory)
$ErrorActionPreference='Stop'
$path=[IO.Path]::GetFullPath($BackupDirectory)
if(-not(Test-Path -LiteralPath $path -PathType Container)){throw '备份目录须预先创建，并授予SQL Server服务账号写入权限。'}
$db=if($Environment -eq 'DEMO'){'ReconisDemo'}else{'ReconisSandbox'}
$file=Join-Path $path "$db-$(Get-Date -Format yyyyMMdd-HHmmss).bak"
$connection=[System.Data.SqlClient.SqlConnection]::new("Server=$Server;Database=master;Integrated Security=True;Encrypt=True;TrustServerCertificate=True")
$connection.Open()
try{$cmd=$connection.CreateCommand();$cmd.CommandTimeout=300;$cmd.CommandText="BACKUP DATABASE [$db] TO DISK=@path WITH COPY_ONLY,CHECKSUM; RESTORE VERIFYONLY FROM DISK=@path WITH CHECKSUM;";$cmd.Parameters.AddWithValue('@path',$file)|Out-Null;$cmd.ExecuteNonQuery()|Out-Null;Write-Host "数据库备份并校验完成：$file。请另行备份data目录中的证据与原件。"}finally{$connection.Close()}
