#Requires -Version 7.4
param(
 [ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',
 [string]$Server='127.0.0.1,1433',
 [string]$JdbcServer
)
$ErrorActionPreference='Stop'
if(-not $JdbcServer){
 $address=$Server -replace '^tcp:',''
 if($address -match '^(?<host>[^,;\\]+),(?<port>\d+)$'){$JdbcServer="$($Matches.host):$($Matches.port)"}
 elseif($address -notmatch '[,;\\]'){$JdbcServer=$address}
 else{throw '使用命名实例时，请通过 -JdbcServer 明确指定应用使用的主机和 TCP 端口。'}
}
if($JdbcServer -match "[;'\r\n]"){throw 'JdbcServer 只能包含 SQL Server 主机和端口。'}
$root=Split-Path -Parent $PSScriptRoot
$db=if($Environment -eq 'DEMO'){'ReconisDemo'}else{'ReconisSandbox'}
$private=Join-Path $root 'private'
New-Item -ItemType Directory -Path $private -Force | Out-Null
$localAccount=[Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $private /inheritance:r /grant:r "${localAccount}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if($LASTEXITCODE){throw '无法限制本地凭据目录权限，初始化已停止。'}
$envFile=Join-Path $private "$Environment.env.ps1"
if(Test-Path -LiteralPath $envFile){throw '配置文件已经存在。初始化只允许新数据库；请用现有配置启动，勿覆盖数据。'}
$java=Get-ChildItem -LiteralPath (Join-Path $root '.tools\java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
$javaBin=if($java){Join-Path $java.FullName 'bin\java.exe'}else{'java'}
. (Join-Path $PSScriptRoot 'release-common.ps1')
$jar=(Resolve-ReconisServerJar -Root $root).JarPath
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))+'aA1!'
$hash=($password | & $javaBin '-Dloader.main=cn.reconis.tools.PasswordHash' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher | Select-Object -Last 1).Trim()
if($hash -notmatch '^\$2[aby]\$'){throw '生成初始化密码散列失败。'}
$builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Server
$builder['Initial Catalog']='master'
$builder['Integrated Security']=$true
$builder['Encrypt']=$true
$builder['TrustServerCertificate']=$true
$connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
$connection.Open()
function Invoke-Sql([string]$sql){$cmd=$connection.CreateCommand();$cmd.CommandText=$sql;$cmd.CommandTimeout=180;$cmd.ExecuteNonQuery()|Out-Null}
try{
 $check=$connection.CreateCommand();$check.CommandText="SELECT DB_ID('$db')";if($check.ExecuteScalar() -isnot [DBNull]){throw "$db 已存在，为保护数据不重复初始化。"}
 Invoke-Sql "CREATE DATABASE [$db];"
 Invoke-Sql "ALTER DATABASE [$db] SET ALLOW_SNAPSHOT_ISOLATION ON;"
 $connection.ChangeDatabase($db)
 foreach($file in @('001_schema.sql','002_constraints_indexes.sql','003_views_functions_procedures.sql','004_triggers_fulltext.sql')){
  $sql=Get-Content -LiteralPath (Join-Path $root "database\$file") -Raw -Encoding UTF8
  foreach($batch in [regex]::Split($sql,'(?im)^GO\s*$')){if($batch.Trim()){Invoke-Sql $batch}}
  Write-Host "已执行 $file"
 }
 foreach($migration in (Get-ChildItem -LiteralPath (Join-Path $root 'database\migrations') -Filter '*.sql' | Sort-Object Name)){
  $sql=Get-Content -LiteralPath $migration.FullName -Raw -Encoding UTF8
  foreach($batch in [regex]::Split($sql,'(?im)^GO\s*$')){if($batch.Trim()){Invoke-Sql $batch}}
  Write-Host "已执行 $($migration.Name)"
 }
 $random=[Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
 $dbPassword=[Convert]::ToBase64String($random)+'aA1!'
 $login=if($Environment -eq 'DEMO'){'reconis_demo'}else{'reconis_sandbox'}
 Invoke-Sql "CREATE LOGIN [$login] WITH PASSWORD=N'$dbPassword',CHECK_POLICY=ON; CREATE USER [$login] FOR LOGIN [$login]; ALTER ROLE reconis_runtime ADD MEMBER [$login];"
 $runtimeDir=if($Environment -eq 'DEMO'){'demo'}else{'sandbox'}
 $config=@"
`$env:RECONIS_ENV='$Environment'
`$env:DB_URL='jdbc:sqlserver://$JdbcServer;databaseName=$db;encrypt=true;trustServerCertificate=true'
`$env:DB_USER='$login'
`$env:DB_PASSWORD='$dbPassword'
`$env:DATA_DIR='$($root.Replace("'","''"))\data\$runtimeDir'
"@
 if($Environment -eq 'DEMO'){
  $seed=(Get-Content -LiteralPath (Join-Path $root 'database\seed_demo.sql') -Raw -Encoding UTF8).Replace('$(DemoPasswordHash)',$hash)
  foreach($batch in [regex]::Split($seed,'(?im)^GO\s*$')){if($batch.Trim()){Invoke-Sql $batch}}
  Set-Content -LiteralPath (Join-Path $private 'demo-accounts.txt') -Value "DEMO账号：admin(管理员), finance(平台财务), auditor(审计), owner(M001负责人), cashier(M001收银), reviewer(M001复核), customer(消费者), otherowner(M002/M003负责人)。`n统一初始密码：$password`n用户名使用英文；admin 的用户名是 admin。请勿提交此文件。" -Encoding UTF8
 }else{
  $seed=(Get-Content -LiteralPath (Join-Path $root 'database\005_bootstrap_admin.sql') -Raw -Encoding UTF8).Replace('$(AdminPasswordHash)',$hash)
  foreach($batch in [regex]::Split($seed,'(?im)^GO\s*$')){if($batch.Trim()){Invoke-Sql $batch}}
  Set-Content -LiteralPath (Join-Path $private 'sandbox-admin.txt') -Value "沙箱管理员：admin（用户名 admin）`n初始密码：$password`n仅本机保存，不提交。" -Encoding UTF8
  $config+=@'

$env:ALIPAY_APP_ID=''
$env:ALIPAY_SELLER_ID=''
$env:ALIPAY_PRIVATE_KEY_FILE=''
$env:ALIPAY_PUBLIC_KEY_FILE=''
$env:ALIPAY_NOTIFY_URL=''
'@
 }
 Set-Content -LiteralPath $envFile -Value $config -Encoding utf8BOM
 Write-Host "数据库已初始化。配置和初始密码仅位于 private 目录。"
}finally{$connection.Close()}
