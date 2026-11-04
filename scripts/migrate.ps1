#Requires -Version 7.4
param(
 [ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',
 [string]$Server='127.0.0.1,1433',
 [switch]$RefreshCampusData
)
$ErrorActionPreference='Stop'
if($RefreshCampusData -and $Environment -ne 'DEMO'){throw '校园预置资料只适用于 ReconisDemo。'}
$root=Split-Path -Parent $PSScriptRoot
$databaseName=if($Environment -eq 'DEMO'){'ReconisDemo'}else{'ReconisSandbox'}
$builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Server
$builder['Initial Catalog']=$databaseName
$builder['Integrated Security']=$true
$builder['Encrypt']=$true
$builder['TrustServerCertificate']=$true
$connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
$connection.Open()
try {
 $check=$connection.CreateCommand()
 $check.CommandText="SELECT CASE WHEN OBJECT_ID('dbo.refund_request','U') IS NOT NULL AND OBJECT_ID('dbo.reconciliation_batch','U') IS NOT NULL THEN 1 ELSE 0 END"
 if($check.ExecuteScalar() -ne 1){throw '目标不是已初始化的 Reconis 数据库，未执行升级。'}
 $lock=$connection.CreateCommand()
 $lock.CommandText="DECLARE @r INT; EXEC @r=sys.sp_getapplock @Resource=N'ReconisSchemaMigration',@LockMode='Exclusive',@LockOwner='Session',@LockTimeout=0; SELECT @r;"
 if([int]$lock.ExecuteScalar() -lt 0){throw '已有数据库升级正在进行。'}
 if($RefreshCampusData){
  $files=@((Join-Path $root 'database\demo\campus_catalog.sql'),(Join-Path $root 'database\demo\refresh_labels.sql'))
 }else{
  $files=@(Get-ChildItem -LiteralPath (Join-Path $root 'database\migrations') -Filter '*.sql' | Sort-Object Name | ForEach-Object FullName)
  $files+=Join-Path $root 'database\003_views_functions_procedures.sql'
  $files+=Join-Path $root 'database\004_triggers_fulltext.sql'
 }
 foreach($file in $files){
  $sql=Get-Content -LiteralPath $file -Raw -Encoding utf8
  foreach($batch in [regex]::Split($sql,'(?im)^GO\s*$')){
   if(-not $batch.Trim()){continue}
   $command=$connection.CreateCommand()
   $command.CommandText=$batch
   $command.CommandTimeout=180
   [void]$command.ExecuteNonQuery()
  }
  Write-Host "已升级 $(Split-Path -Leaf $file)"
 }
 if($RefreshCampusData){Write-Host "数据库 $databaseName 的校园预置资料已刷新。"}
 else{Write-Host "数据库 $databaseName 升级完成，已有业务数据已保留。"}
} finally { $connection.Dispose() }
