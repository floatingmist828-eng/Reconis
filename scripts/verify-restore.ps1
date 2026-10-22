#Requires -Version 7.4
param(
 [ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',
 [string]$Server='127.0.0.1,1433',
 [string]$BackupDirectory,
 [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(-not $BackupDirectory){$BackupDirectory=Join-Path $root 'artifacts/database-backup'}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $root 'artifacts/validation'}
$BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory)
if(-not(Test-Path -LiteralPath $BackupDirectory -PathType Container)){throw '备份目录须存在且 SQL Server 服务账号可写。'}
[void](New-Item -ItemType Directory -Path $OutputDirectory -Force)
$sourceName=if($Environment -eq 'DEMO'){'ReconisDemo'}else{'ReconisSandbox'}
$dataPath=Join-Path $root ('data/'+ $(if($Environment -eq 'DEMO'){'demo'}else{'sandbox'}))
$token=[Guid]::NewGuid().ToString('N')
$restoreName='ReconisRestore_'+$token
$stamp=Get-Date -Format yyyyMMdd-HHmmss
$backup=Join-Path $BackupDirectory "$sourceName-restorecheck-$stamp.bak"
$archive=Join-Path $BackupDirectory "$sourceName-restorecheck-$stamp.zip"
$scratchRoot=[IO.Path]::GetFullPath((Join-Path $root 'artifacts/restore-check'))
$scratch=[IO.Path]::GetFullPath((Join-Path $scratchRoot $token))
$record=[ordered]@{startedAt=(Get-Date).ToString('o');source=$sourceName;temporaryDatabase=$restoreName;status='RUNNING';databaseCleanup='PENDING';checks=@();backup=$backup;filesArchive=$archive}
$builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Server;$builder['Initial Catalog']='master';$builder['Integrated Security']=$true
$builder['Encrypt']=$true;$builder['TrustServerCertificate']=$true;$builder['Connect Timeout']=10;$builder['Pooling']=$false
$conn=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
function Read-Sql([string]$Text,[hashtable]$Parameters=@{}) {
 $cmd=$conn.CreateCommand();$cmd.CommandTimeout=180;$cmd.CommandText=$Text
 foreach($key in $Parameters.Keys){[void]$cmd.Parameters.AddWithValue($key,$Parameters[$key])}
 try{$table=[Data.DataTable]::new();$reader=$cmd.ExecuteReader();try{$table.Load($reader)}finally{$reader.Dispose()};return ,$table}finally{$cmd.Dispose()}
}
function Write-Sql([string]$Text,[hashtable]$Parameters=@{}) {
 $cmd=$conn.CreateCommand();$cmd.CommandTimeout=300;$cmd.CommandText=$Text
 foreach($key in $Parameters.Keys){[void]$cmd.Parameters.AddWithValue($key,$Parameters[$key])}
 try{[void]$cmd.ExecuteNonQuery()}finally{$cmd.Dispose()}
}
function Text-Hash([string]$Value){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()}
function Get-DatabaseFingerprint([string]$Name){
 $conn.ChangeDatabase($Name)
 $result=[ordered]@{}
 $tables=Read-Sql 'SELECT name FROM sys.tables WHERE is_ms_shipped=0 ORDER BY name'
 foreach($row in $tables.Rows){
  $table=[string]$row.name
  if($table -notmatch '^[a-z_]+$'){throw '未知表名，停止恢复验收。'}
  $cmd=$conn.CreateCommand();$cmd.CommandTimeout=60
  $cmd.CommandText="SELECT (SELECT * FROM dbo.[$table] ORDER BY id FOR JSON PATH,INCLUDE_NULL_VALUES)"
  try{$json=[string]$cmd.ExecuteScalar();$result[$table]=Text-Hash $json}finally{$cmd.Dispose()}
 }
 $roles=Read-Sql "SELECT role.name role_name,member.name member_name FROM sys.database_role_members r JOIN sys.database_principals role ON role.principal_id=r.role_principal_id JOIN sys.database_principals member ON member.principal_id=r.member_principal_id ORDER BY role.name,member.name"
 $roleRows=@($roles.Rows|ForEach-Object { "$($_.role_name):$($_.member_name)" })
 $result['roles']=Text-Hash ($roleRows -join "`n")
 foreach($entry in @(
  @{key='permissions';sql="SELECT state,class,major_id,minor_id,grantee_principal_id,grantor_principal_id,permission_name FROM sys.database_permissions ORDER BY class,major_id,minor_id,grantee_principal_id,grantor_principal_id,permission_name FOR JSON PATH,INCLUDE_NULL_VALUES"},
  @{key='users';sql="SELECT name,type,authentication_type,CONVERT(VARCHAR(200),sid,2) sid,default_schema_name FROM sys.database_principals WHERE type IN ('S','U','G') AND name NOT IN ('dbo','guest','INFORMATION_SCHEMA','sys') ORDER BY name FOR JSON PATH,INCLUDE_NULL_VALUES"}
 )){
  $cmd=$conn.CreateCommand();$cmd.CommandTimeout=30;$cmd.CommandText='SELECT ('+$entry.sql+')'
  try{$result[$entry.key]=Text-Hash ([string]$cmd.ExecuteScalar())}finally{$cmd.Dispose()}
 }
 return $result
}
function Get-FilesFingerprint([string]$Directory){
 $result=[ordered]@{}
 if(Test-Path -LiteralPath $Directory){
  $rootItem=Get-Item -LiteralPath $Directory -Force
  if($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '数据根目录为链接，停止文件归档。'}
  $all=@(Get-ChildItem -LiteralPath $Directory -Force -Recurse)
  if($all | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }){throw '数据目录含目录或文件链接，停止文件归档。'}
  foreach($file in ($all | Where-Object {-not $_.PSIsContainer} | Sort-Object FullName)){
   $relative=[IO.Path]::GetRelativePath($Directory,$file.FullName).Replace('\','/')
   $result[$relative]=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  }
 }
 return $result
}
try{
 $conn.Open()
 $original=Get-DatabaseFingerprint $sourceName
 $originalFiles=Get-FilesFingerprint $dataPath
 $conn.ChangeDatabase('master')
 Write-Sql "BACKUP DATABASE [$sourceName] TO DISK=@path WITH COPY_ONLY,CHECKSUM; RESTORE VERIFYONLY FROM DISK=@path WITH CHECKSUM;" @{'@path'=$backup}
 if(Test-Path -LiteralPath $dataPath){[IO.Compression.ZipFile]::CreateFromDirectory($dataPath,$archive)}
 else{[IO.Directory]::CreateDirectory($scratch)|Out-Null;[IO.Compression.ZipFile]::CreateFromDirectory($scratch,$archive)}
 $after=Get-DatabaseFingerprint $sourceName
 $afterFiles=Get-FilesFingerprint $dataPath
 if((ConvertTo-Json $original -Compress) -ne (ConvertTo-Json $after -Compress) -or (ConvertTo-Json $originalFiles -Compress) -ne (ConvertTo-Json $afterFiles -Compress)){
  throw '备份期间数据发生变化；请停止本地应用写入后重新验收，本次不宣称一致恢复。'
 }
 $conn.ChangeDatabase('master')
 $paths=Read-Sql "SELECT CONVERT(NVARCHAR(4000),SERVERPROPERTY('InstanceDefaultDataPath')) data_path,CONVERT(NVARCHAR(4000),SERVERPROPERTY('InstanceDefaultLogPath')) log_path"
 $files=Read-Sql 'RESTORE FILELISTONLY FROM DISK=@path' @{'@path'=$backup}
 $moves=@();$parameters=@{'@path'=$backup};$index=0
 foreach($file in $files.Rows){
  if($file.Type -notin @('D','L')){throw '恢复验收暂不支持此数据库文件类型。'}
  $base=if($file.Type -eq 'L'){$paths.Rows[0].log_path}else{$paths.Rows[0].data_path}
  if([string]::IsNullOrWhiteSpace($base)){throw 'SQL Server 未提供默认文件目录。'}
  $target=Join-Path $base "$restoreName-$index.$(if($file.Type -eq 'L'){'ldf'}else{'mdf'})"
  $moves+="MOVE @logical$index TO @physical$index";$parameters["@logical$index"]=[string]$file.LogicalName;$parameters["@physical$index"]=$target;$index++
 }
 if($restoreName -notmatch '^ReconisRestore_[a-f0-9]{32}$'){throw '隔离库名称校验失败。'}
 Write-Sql "RESTORE DATABASE [$restoreName] FROM DISK=@path WITH $($moves -join ','),RECOVERY,CHECKSUM;" $parameters
 $restored=Get-DatabaseFingerprint $restoreName
 if((ConvertTo-Json $original -Compress) -ne (ConvertTo-Json $restored -Compress)){throw '恢复库的数据或角色与原库不一致。'}
 $record.checks+='All table SHA256 fingerprints and database role memberships match'
 $record.checks+='Explicit GRANT/DENY permissions and user SID mappings match'
 $integrity=Read-Sql "DBCC CHECKDB ([$restoreName]) WITH NO_INFOMSGS,ALL_ERRORMSGS,TABLERESULTS"
 if($integrity.Rows.Count -gt 0){throw 'DBCC CHECKDB 返回完整性错误。'}
 $record.checks+='DBCC CHECKDB passed'
 $totals=Read-Sql "SELECT COUNT_BIG(*) entries,COALESCE(SUM(CONVERT(DECIMAL(38,0),CASE WHEN entry_type='RECEIPT' THEN amount_cent ELSE -amount_cent END)),0) net_cent FROM v_transaction_fact"
 $record.ledger=@{entries=[string]$totals.Rows[0].entries;netCent=[string]$totals.Rows[0].net_cent}
 $record.checks+='Restored transaction view and exact amount aggregation passed'
 $runtime=if($Environment -eq 'DEMO'){'reconis_demo'}else{'reconis_sandbox'}
 $runtimeCheck=Read-Sql "EXECUTE AS USER='$runtime'; SELECT COUNT_BIG(*) entries FROM dbo.v_transaction_fact; REVERT;"
 if([string]$runtimeCheck.Rows[0].entries -ne $record.ledger.entries){throw '恢复库运行用户无法读取正确流水。'}
 $denied=Read-Sql "EXECUTE AS USER='$runtime'; BEGIN TRY DELETE dbo.transaction_entry WHERE 1=0; SELECT 0 error_code; END TRY BEGIN CATCH SELECT ERROR_NUMBER() error_code; END CATCH; REVERT;"
 if([int]$denied.Rows[0].error_code -ne 229){throw '恢复库运行用户未保留财务流水删除权限限制。'}
 $record.checks+='Runtime user can read transaction view and cannot delete financial facts'
 [IO.Directory]::CreateDirectory($scratch)|Out-Null
 [IO.Compression.ZipFile]::ExtractToDirectory($archive,$scratch)
 $restoredFiles=Get-FilesFingerprint $scratch
 if((ConvertTo-Json $originalFiles -Compress) -ne (ConvertTo-Json $restoredFiles -Compress)){throw '证据文件归档恢复散列不一致。'}
 $record.checks+='All archived evidence and channel files extracted with matching SHA256'
 $record.fileCount=$originalFiles.Count;$record.tableCount=$original.Count-3;$record.status='PASSED'
}catch{$record.status='FAILED';$record.error=$_.Exception.Message;throw}
finally{
 try{
  $conn.Dispose()
  $conn=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
  $conn.Open()
  if($conn.State -eq 'Open'){
   if($restoreName -match '^ReconisRestore_[a-f0-9]{32}$'){
    Write-Sql "IF DB_ID(@name) IS NOT NULL BEGIN ALTER DATABASE [$restoreName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$restoreName]; END" @{'@name'=$restoreName}
    $record.databaseCleanup='COMPLETED'
   }
  }
  if((Test-Path -LiteralPath $scratch) -and $scratch.StartsWith($scratchRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $scratch) -eq $token){Remove-Item -LiteralPath $scratch -Recurse -Force}
 }catch{$record.cleanupError=$_.Exception.Message;$record.databaseCleanup='PENDING';$record.status='FAILED'}
 $conn.Dispose();$record.finishedAt=(Get-Date).ToString('o')
 $output=Join-Path $OutputDirectory "restore-$Environment-$stamp.json"
 $record|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $output -Encoding utf8
 Write-Host "恢复验收：$($record.status)，记录：$output"
}
if($record.status -ne 'PASSED'){throw '恢复验收未通过。'}
