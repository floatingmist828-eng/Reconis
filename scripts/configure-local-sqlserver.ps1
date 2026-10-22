#Requires -Version 7.4
param(
 [ValidateRange(256,1024)][int]$MinMemoryMb=512,
 [ValidateRange(1024,4096)][int]$MaxMemoryMb=2048,
 [ValidateRange(1,8)][int]$MaxDop=4
)
$ErrorActionPreference='Stop'
if($MinMemoryMb -ge $MaxMemoryMb){throw '最小内存必须小于最大内存。'}
# This profile is only for Reconis on the local shared development computer.
# Minimum memory does not reserve physical RAM or prevent Windows from paging it.
$connection=[System.Data.SqlClient.SqlConnection]::new('Server=127.0.0.1,1433;Database=master;Integrated Security=True;Encrypt=True;TrustServerCertificate=True;Connect Timeout=5;Pooling=False;ConnectRetryCount=0')
try {
 $connection.Open()
 $identity=$connection.CreateCommand()
 $identity.CommandTimeout=5
 $identity.CommandText="SELECT CASE WHEN CONVERT(NVARCHAR(128),SERVERPROPERTY('MachineName'))=@machine AND CONVERT(NVARCHAR(128),SERVERPROPERTY('InstanceName'))=N'RECONIS' THEN 1 ELSE 0 END"
 [void]$identity.Parameters.AddWithValue('@machine',[Environment]::MachineName)
 if($identity.ExecuteScalar() -ne 1){throw '目标不是本机 RECONIS 实例，未修改配置。'}
 $query=$connection.CreateCommand()
 $query.CommandTimeout=10
 $query.CommandText="SELECT name,CONVERT(INT,value_in_use) AS value FROM sys.configurations WHERE name IN ('min server memory (MB)','max server memory (MB)','max degree of parallelism') ORDER BY name"
 function Read-Settings {
  $reader=$query.ExecuteReader()
  try {while($reader.Read()){[pscustomobject]@{name=$reader.GetString(0);value=$reader.GetInt32(1)}}} finally {$reader.Dispose()}
 }
 $before=@(Read-Settings)
 $command=$connection.CreateCommand()
 $command.CommandTimeout=15
 $command.CommandText=@'
DECLARE @advanced INT=(SELECT CONVERT(INT,value_in_use) FROM sys.configurations WHERE name='show advanced options');
IF @advanced=0 BEGIN EXEC sys.sp_configure 'show advanced options',1; RECONFIGURE; END;
BEGIN TRY
 EXEC sys.sp_configure 'min server memory (MB)',@minimum;
 EXEC sys.sp_configure 'max server memory (MB)',@maximum;
 EXEC sys.sp_configure 'max degree of parallelism',@dop;
 RECONFIGURE;
END TRY
BEGIN CATCH
 IF @advanced=0 BEGIN EXEC sys.sp_configure 'show advanced options',0; RECONFIGURE; END;
 THROW;
END CATCH;
IF @advanced=0 BEGIN EXEC sys.sp_configure 'show advanced options',0; RECONFIGURE; END;
'@
 [void]$command.Parameters.AddWithValue('@minimum',$MinMemoryMb)
 [void]$command.Parameters.AddWithValue('@maximum',$MaxMemoryMb)
 [void]$command.Parameters.AddWithValue('@dop',$MaxDop)
 [void]$command.ExecuteNonQuery()
 $after=@(Read-Settings)
 $expected=@{'min server memory (MB)'=$MinMemoryMb;'max server memory (MB)'=$MaxMemoryMb;'max degree of parallelism'=$MaxDop}
 foreach($setting in $after){if($setting.value -ne $expected[$setting.name]){throw 'SQL Server 配置回读与请求不一致。'}}
 $directory=Join-Path (Split-Path -Parent $PSScriptRoot) 'artifacts/validation'
 [void](New-Item -ItemType Directory -Path $directory -Force)
 $record=Join-Path $directory ('sql-resource-settings-'+(Get-Date -Format yyyyMMdd-HHmmss)+'.json')
 [pscustomobject]@{appliedAt=(Get-Date).ToString('o');instance='RECONIS';before=$before;after=$after} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $record -Encoding utf8
 Write-Host "本机 RECONIS 配置已应用并回读：min=$MinMemoryMb MB，max=$MaxMemoryMb MB，MAXDOP=$MaxDop。"
 Write-Host "前后配置记录：$record。仍需保留足够系统内存；此设置不能阻止 Windows 分页。"
} finally {$connection.Dispose()}
