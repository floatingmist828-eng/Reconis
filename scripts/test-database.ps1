#Requires -Version 7.4
param(
 [string]$Server='127.0.0.1,1433',
 [string]$JdbcServer='127.0.0.1:1433',
 [string]$MavenSettings,
 [switch]$AllTests,
 [string]$BackendDirectory,
 [switch]$Package,
 [ValidateRange(5,60)][int]$LoginTimeoutSeconds=10,
 [ValidateRange(10,180)][int]$QueryTimeoutSeconds=30,
 [ValidateRange(1,30)][int]$CancelQueryTimeoutSeconds=5,
 [ValidateRange(256,2048)][int]$TestHeapMb=512,
 [ValidateRange(128,1024)][int]$MavenHeapMb=256
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(-not $BackendDirectory){$BackendDirectory=Join-Path $root 'backend'}
$backendPath=[IO.Path]::GetFullPath((Resolve-Path -LiteralPath $BackendDirectory).ProviderPath).TrimEnd('\','/')
$defaultBackend=[IO.Path]::GetFullPath((Join-Path $root 'backend'))
$snapshotRoot=[IO.Path]::GetFullPath((Join-Path $root 'artifacts\build'))+[IO.Path]::DirectorySeparatorChar
$isSnapshot=$backendPath.StartsWith($snapshotRoot,[StringComparison]::OrdinalIgnoreCase) -and
 ([IO.Path]::GetRelativePath($snapshotRoot,$backendPath) -match '^[^\\/]+[\\/]backend$')
if($backendPath -ne $defaultBackend -and -not $isSnapshot){
 throw 'BackendDirectory 仅允许本项目 backend 或 artifacts/build/<版本>/backend。'
}
# Reject links inside the selected tree so clean cannot follow a junction outside this repository.
$ancestor=Get-Item -LiteralPath $backendPath
while($ancestor.FullName -ne $root){
 if($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'BackendDirectory 不允许经过目录链接。'}
 $ancestor=$ancestor.Parent
 if(-not $ancestor){throw 'BackendDirectory 不在本项目中。'}
}
if(-not(Test-Path -LiteralPath (Join-Path $backendPath 'mvnw.cmd') -PathType Leaf) -or
   -not(Test-Path -LiteralPath (Join-Path $backendPath 'pom.xml') -PathType Leaf)){
 throw 'BackendDirectory 缺少 Maven 项目或启动脚本。'
}
if($Package -and (Test-Path -LiteralPath (Join-Path $backendPath 'target')) -and
   ((Get-Item -LiteralPath (Join-Path $backendPath 'target')).Attributes -band [IO.FileAttributes]::ReparsePoint)){
 throw '构建 target 目录不能是目录链接。'
}
$builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Server
$builder['Initial Catalog']='master'
$builder['Integrated Security']=$true
$builder['Encrypt']=$true
$builder['TrustServerCertificate']=$true
$builder['Connect Timeout']=$LoginTimeoutSeconds
# JDBC query/cancellation limits use seconds; its socket timeout uses milliseconds.
# Allow cancellation to finish before the socket deadline closes an unresponsive connection.
$socketTimeoutMs=[Math]::Max($LoginTimeoutSeconds,$QueryTimeoutSeconds+$CancelQueryTimeoutSeconds+10)*1000
$connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
$login='reconis_test_'+[Guid]::NewGuid().ToString('N')
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))+'aA1!'
$previous=@{}
foreach($name in @('RECONIS_SQL_TEST_URL','RECONIS_SQL_TEST_USER','RECONIS_SQL_TEST_PASSWORD','JAVA_HOME','MAVEN_OPTS')){
 $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
$loginCreationAttempted=$false
try {
 $connection.Open()
 $check=$connection.CreateCommand()
 $check.CommandTimeout=$QueryTimeoutSeconds
 $check.CommandText="SELECT CONVERT(INT,FULLTEXTSERVICEPROPERTY('IsFullTextInstalled'))"
 if($check.ExecuteScalar() -ne 1){throw '数据库测试需要 SQL Server 全文检索组件。'}
 $command=$connection.CreateCommand()
 $command.CommandTimeout=$QueryTimeoutSeconds
 $command.CommandText="DECLARE @sql NVARCHAR(MAX)=N'CREATE LOGIN '+QUOTENAME(@login)+N' WITH PASSWORD='+QUOTENAME(@secret,'''')+N',CHECK_POLICY=ON; GRANT CREATE ANY DATABASE TO '+QUOTENAME(@login)+N';'; EXEC sys.sp_executesql @sql;"
 [void]$command.Parameters.AddWithValue('@login',$login)
 [void]$command.Parameters.AddWithValue('@secret',$password)
 $loginCreationAttempted=$true
 [void]$command.ExecuteNonQuery()
 $env:RECONIS_SQL_TEST_URL="jdbc:sqlserver://$JdbcServer;databaseName=master;encrypt=true;trustServerCertificate=true;loginTimeout=$LoginTimeoutSeconds;queryTimeout=$QueryTimeoutSeconds;cancelQueryTimeout=$CancelQueryTimeoutSeconds;socketTimeout=$socketTimeoutMs;connectRetryCount=0"
 $env:RECONIS_SQL_TEST_USER=$login
 $env:RECONIS_SQL_TEST_PASSWORD=$password
 $jdk=Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
 if($jdk){$env:JAVA_HOME=$jdk.FullName}
 else{$env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)}
 # Bound both JVMs: Maven's own heap limit does not constrain the forked test JVM.
 # Append our limits while preserving other caller options; restore the original value in finally.
 $env:MAVEN_OPTS=(@($previous['MAVEN_OPTS'],"-Xms32m -Xmx${MavenHeapMb}m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=4") -join ' ').Trim()
 $testJvmArgs="-Xms32m -Xmx${TestHeapMb}m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:ActiveProcessorCount=4"
 $mavenArgs=@('-B','-ntp','-DforkCount=1','-DreuseForks=true',"-DargLine=$testJvmArgs")
 if($Package){$mavenArgs+=@('clean','package')}else{$mavenArgs+='test'}
 if(-not $AllTests){$mavenArgs+='-Dtest=*DatabaseTest'}
 if($MavenSettings){$mavenArgs+=@('-s',[IO.Path]::GetFullPath($MavenSettings))}
 Push-Location $backendPath
 try {
  & (Join-Path $backendPath 'mvnw.cmd') @mavenArgs
  if($LASTEXITCODE){throw "数据库集成测试或打包失败，请查看 $backendPath/target/surefire-reports。"}
 } finally {Pop-Location}
 Write-Host 'SQL Server 集成测试完成；测试仅使用独立临时数据库。'
} finally {
 foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
 $connection.Dispose()
 # Creation may have succeeded even when the following GRANT or response failed.
 # Use a fresh, non-pooled connection instead of reusing a possibly broken admin session.
 if($loginCreationAttempted){
  $cleanupBuilder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new($builder.ConnectionString)
  $cleanupBuilder['Pooling']=$false
  $cleanupConnection=[System.Data.SqlClient.SqlConnection]::new($cleanupBuilder.ConnectionString)
  try {
   $cleanupConnection.Open()
   $cleanup=$cleanupConnection.CreateCommand()
   $cleanup.CommandTimeout=[Math]::Min($QueryTimeoutSeconds,10)
   $cleanup.CommandText=@'
DECLARE @sid VARBINARY(85)=(SELECT sid FROM sys.server_principals WHERE name=@login AND type='S');
IF @sid IS NOT NULL
BEGIN
 IF EXISTS(SELECT 1 FROM sys.databases WHERE owner_sid=@sid)
  SELECT name AS owned_database FROM sys.databases WHERE owner_sid=@sid ORDER BY name;
 ELSE
 BEGIN
  DECLARE @sql NVARCHAR(MAX)=N'DROP LOGIN '+QUOTENAME(@login);
  EXEC sys.sp_executesql @sql;
 END;
END;
'@
   [void]$cleanup.Parameters.AddWithValue('@login',$login)
   $ownedDatabases=@()
   $reader=$cleanup.ExecuteReader()
   try {
    while($reader.Read()){$ownedDatabases+=$reader.GetString(0)}
   } finally {$reader.Dispose()}
   if($ownedDatabases.Count){
    Write-Warning "临时测试登录 $login 仍拥有数据库：$($ownedDatabases -join ', ')。已保留这些数据库和登录，请检查失败原因后人工清理；未执行批量删除。"
   }
  } catch {
   Write-Warning "临时测试登录 $login 的清理未能确认，需要检查该精确登录名：$($_.Exception.Message)"
  } finally {$cleanupConnection.Dispose()}
 }
}
