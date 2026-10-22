#Requires -Version 7.4
[CmdletBinding()]
param(
 [string]$Server='127.0.0.1,1433',
 [ValidateRange(1000,200000)][int[]]$Sizes=@(10000,100000),
 [ValidateRange(30,600)][int]$QueryTimeoutSeconds=180,
 [ValidateRange(30,600)][int]$FulltextWaitSeconds=180
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$runId=Get-Date -Format 'yyyyMMdd-HHmmss'
$database='ReconisPerformance_'+[Guid]::NewGuid().ToString('N')
$output=Join-Path $root "artifacts\validation\performance-$runId"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$report=[ordered]@{runId=$runId;startedAt=[DateTimeOffset]::Now.ToString('o');database=$database;status='RUNNING';cleanup='PENDING';stages=@();migrations=@();notes=@('顺序执行；不清除服务器缓存。first 表示本次数据档位首次关键词查询，不宣称冷缓存。','耗时为客户端完成读取结果的墙钟时间，包含统计信息与实际执行计划采集开销，不等同于纯服务器执行时间。','已应用009支付订单索引迁移，旧索引超时基准另行保留。不同运行受编译、缓存与资源状态影响，不视作严格A/B纯耗时实验。','保存 STATISTICS IO/TIME 与实际执行计划，仅报告本次实测数据。','仅隔离临时数据库使用生成数据，订单/支付/流水/账单按同一事件键构建；2%金额不符。')}
$builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Server; $builder['Initial Catalog']='master'; $builder['Integrated Security']=$true
$builder['Encrypt']=$true; $builder['TrustServerCertificate']=$true; $builder['Connect Timeout']=10; $builder['Pooling']=$false
$connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
$creationAttempted=$false
$messages=[Collections.Generic.List[string]]::new()
$handler=[System.Data.SqlClient.SqlInfoMessageEventHandler]{param($sender,$event) $messages.Add($event.Message)}
$connection.add_InfoMessage($handler)

function Invoke-Scalar([string]$Sql) {
 $command=$connection.CreateCommand(); $command.CommandText=$Sql; $command.CommandTimeout=$QueryTimeoutSeconds
 try {return $command.ExecuteScalar()} finally {$command.Dispose()}
}
function Invoke-Sql([string]$Sql) {
 $command=$connection.CreateCommand(); $command.CommandText=$Sql; $command.CommandTimeout=$QueryTimeoutSeconds
 try {[void]$command.ExecuteNonQuery()} finally {$command.Dispose()}
}
function Measure-Sql([string]$Name,[string]$Sql,[hashtable]$Parameters=@{},[switch]$Plans) {
 $messages.Clear()
 $command=$connection.CreateCommand(); $command.CommandTimeout=$QueryTimeoutSeconds
 $command.CommandText="SET STATISTICS IO ON; SET STATISTICS TIME ON; " + $(if($Plans){'SET STATISTICS XML ON; '}else{''}) + $Sql
 foreach($key in $Parameters.Keys){[void]$command.Parameters.AddWithValue("@$key",$Parameters[$key])}
 $timer=[Diagnostics.Stopwatch]::StartNew(); $rows=[Collections.Generic.List[object]]::new(); $planFiles=[Collections.Generic.List[string]]::new(); $rowCount=0L
 try {
  $reader=$command.ExecuteReader()
  try {
   do {
    while($reader.Read()) {
     if($reader.FieldCount -eq 1 -and $reader.GetName(0) -like '*Showplan*') {
      $planPath=Join-Path $output "$Name-$($planFiles.Count+1).sqlplan"
      [IO.File]::WriteAllText($planPath,[string]$reader.GetValue(0),[Text.UTF8Encoding]::new($false)); $planFiles.Add($planPath)
     } else {
      $rowCount++
      if($rows.Count -lt 100){
       $row=[ordered]@{}
       for($i=0;$i -lt $reader.FieldCount;$i++){$row[$reader.GetName($i)]=$(if($reader.IsDBNull($i)){$null}else{$reader.GetValue($i)})}
       $rows.Add($row)
      }
     }
    }
   } while($reader.NextResult())
  } finally {$reader.Dispose()}
  $timer.Stop()
  return [ordered]@{name=$Name;elapsedMs=[Math]::Round($timer.Elapsed.TotalMilliseconds,3);rowCount=$rowCount;rows=@($rows.ToArray());statistics=@($messages.ToArray());actualPlans=@($planFiles.ToArray());status='PASSED'}
 } catch {
  $timer.Stop()
  $report.failedMeasurement=[ordered]@{name=$Name;elapsedMs=[Math]::Round($timer.Elapsed.TotalMilliseconds,3);rowCount=$rowCount;statistics=@($messages.ToArray());actualPlans=@($planFiles.ToArray());error=$_.Exception.Message;status='FAILED'}
  throw
 } finally {
  $command.Dispose()
  if($connection.State -eq 'Open'){Invoke-Sql 'SET STATISTICS XML OFF; SET STATISTICS IO OFF; SET STATISTICS TIME OFF;'}
 }
}

try {
 $connection.Open()
 if([int](Invoke-Scalar "SELECT FULLTEXTSERVICEPROPERTY('IsFullTextInstalled')") -ne 1){throw '需要 SQL Server 原生全文组件。'}
 $report.sqlVersion=[string](Invoke-Scalar "SELECT SERVERPROPERTY('ProductVersion')")
 $report.serverMaxMemoryMb=[int](Invoke-Scalar "SELECT value_in_use FROM sys.configurations WHERE name='max server memory (MB)'")
 $creationAttempted=$true
 Invoke-Sql "CREATE DATABASE [$database]; ALTER DATABASE [$database] SET ALLOW_SNAPSHOT_ISOLATION ON; ALTER DATABASE [$database] SET RECOVERY SIMPLE;"
 $connection.ChangeDatabase($database)
 Invoke-Sql 'ALTER DATABASE SCOPED CONFIGURATION SET MAXDOP=2;'
 foreach($file in @('001_schema.sql','002_constraints_indexes.sql','003_views_functions_procedures.sql','004_triggers_fulltext.sql')) {
  foreach($batch in [regex]::Split([IO.File]::ReadAllText((Join-Path $root "database\$file")),'(?im)^GO\s*$')) {if($batch.Trim()){Invoke-Sql $batch}}
 }
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'database\migrations') -Filter '*.sql' | Sort-Object Name) {
  foreach($batch in [regex]::Split([IO.File]::ReadAllText($file.FullName),'(?im)^GO\s*$')) {if($batch.Trim()){Invoke-Sql $batch}}
  $report.migrations+=@{name=$file.Name;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
 }
 if([int](Invoke-Scalar 'SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0') -ne 15){throw '隔离库业务表数量不是15，停止基准。'}
 Invoke-Sql @'
INSERT app_user(username,password_hash,display_name,platform_role,status) VALUES(N'perf_finance','not-a-login',N'基准财务','PLATFORM_FINANCE','ACTIVE'),(N'perf_owner1','not-a-login',N'商户一','MEMBER','ACTIVE'),(N'perf_owner2','not-a-login',N'商户二','MEMBER','ACTIVE');
INSERT merchant(merchant_code,name,category_code,status) VALUES('PERF1',N'基准食堂一',N'餐饮','ACTIVE'),('PERF2',N'基准食堂二',N'餐饮','ACTIVE');
INSERT merchant_member(merchant_id,user_id,role_code) SELECT m.id,u.id,'OWNER' FROM merchant m JOIN app_user u ON u.username=CONCAT('perf_owner',RIGHT(m.merchant_code,1));
INSERT store(merchant_id,store_code,name) SELECT m.id,CONCAT('PS',n.n),CONCAT(N'基准窗口',n.n) FROM (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) n(n) JOIN merchant m ON m.merchant_code=CONCAT('PERF',n.n%2+1);
'@
 $previous=0
 foreach($size in ($Sizes | Sort-Object -Unique)) {
  Write-Host "性能基准：$size 条支付流水（隔离库，顺序执行）。"
  $stage=[ordered]@{size=$size;measurements=@();assertions=@();status='RUNNING'}
  $report.stages+=,$stage
  $seedSql=@'
SET NOCOUNT ON; SET XACT_ABORT ON;
BEGIN TRANSACTION;
WITH digits(n) AS(SELECT n FROM(VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) d(n)), numbers(n) AS(
 SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n+1 FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e CROSS JOIN digits f)
SELECT n,CONVERT(BIGINT,100+n%9900) amount_cent,DATEADD(SECOND,n%86400,DATEADD(DAY,n%28,CONVERT(DATETIME2(3),'2026-01-01'))) occurred_at INTO #seed FROM numbers WHERE n>@previous AND n<=@size;
INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status,created_at)
 SELECT CONCAT('PO',n),s.id,u.id,CONVERT(VARCHAR(36),NEWID()),N'基准午餐','PAID',g.occurred_at FROM #seed g JOIN store s ON s.store_code=CONCAT('PS',n%10) CROSS JOIN (SELECT id FROM app_user WHERE username='perf_finance') u;
INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent)
 SELECT o.id,1,N'午餐套餐',N'餐饮',1,g.amount_cent FROM #seed g JOIN business_order o ON o.order_no=CONCAT('PO',g.n);
INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,channel_trade_no,status,is_primary,confirmed_at,created_at)
 SELECT o.id,CONVERT(VARCHAR(36),NEWID()),CONCAT('PT',g.n),'DEMO','PERF_APP','PERF_SELLER',CONCAT('PC',g.n),'SUCCEEDED',1,g.occurred_at,g.occurred_at FROM #seed g JOIN business_order o ON o.order_no=CONCAT('PO',g.n);
INSERT transaction_entry(entry_no,event_key,entry_type,payment_id,amount_cent,channel_occurred_at)
 SELECT CONCAT('PE',g.n),CONCAT('P:PT',g.n),'RECEIPT',p.id,g.amount_cent,g.occurred_at FROM #seed g JOIN payment_attempt p ON p.out_trade_no=CONCAT('PT',g.n);
DROP TABLE #seed;
COMMIT;
SELECT COUNT_BIG(*) event_count,CONVERT(VARCHAR(40),SUM(CONVERT(DECIMAL(38,0),amount_cent))) amount_cent FROM transaction_entry;
'@
  $seed=Measure-Sql "$size-seed" $seedSql @{previous=$previous;size=$size}
  $stage.measurements+=,$seed
  if([long]$seed.rows[0].event_count -ne $size){throw '生成流水数量错误。'}
  $expectedAmount=0L; for($n=1;$n -le $size;$n++){$expectedAmount+=100+$n%9900}
  if([decimal]$seed.rows[0].amount_cent -ne $expectedAmount){throw '生成流水金额与独立计算不一致。'}
  $stage.assertions+=@("流水数量=$size","流水金额=$expectedAmount 分，独立逐笔计算相等")
  foreach($tenant in @(1,2)) {
   $merchant=[long](Invoke-Scalar "SELECT id FROM merchant WHERE merchant_code='PERF$tenant'")
   $actor=[long](Invoke-Scalar "SELECT id FROM app_user WHERE username='perf_owner$tenant'")
   $batchId=[long](Invoke-Scalar "INSERT reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,completeness,status) OUTPUT INSERTED.id VALUES('PERF-$size-$tenant','MERCHANT',$merchant,'2026-01-01','2026-01-30','XML','DECLARED_COMPLETE','READY')")
   Invoke-Sql @"
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at)
 SELECT $batchId,CONVERT(INT,SUBSTRING(f.out_trade_no,3,20)),'RECEIPT',f.out_trade_no,f.channel_trade_no,f.amount_cent+CASE WHEN CONVERT(INT,SUBSTRING(f.out_trade_no,3,20))%100 IN(0,1) THEN 1 ELSE 0 END,f.channel_occurred_at
 FROM v_transaction_fact f WHERE f.merchant_id=$merchant;
"@
   $stage.measurements+=,(Measure-Sql "$size-reconcile-tenant$tenant" 'EXEC dbo.usp_run_reconciliation @batch_id=@batchId;' @{batchId=$batchId} -Plans)
   $verify=Measure-Sql "$size-verify-tenant$tenant" 'SELECT COUNT_BIG(*) result_count,SUM(CASE WHEN classification=''AMOUNT_MISMATCH'' THEN CONVERT(BIGINT,1) ELSE 0 END) mismatch_count,SUM(CASE WHEN classification NOT IN(''MATCHED'',''AMOUNT_MISMATCH'') THEN 1 ELSE 0 END) unexpected_count FROM reconciliation_result WHERE batch_id=@batchId;' @{batchId=$batchId}
   $stage.measurements+=,$verify
   $expectedTenant=[Math]::Floor($size/2); if($tenant -eq 2){$expectedTenant=[Math]::Ceiling($size/2)}
   $expectedMismatch=if($tenant -eq 1){[Math]::Floor($size/100)}else{[Math]::Floor(($size-1)/100)+1}
   if([long]$verify.rows[0].result_count -ne $expectedTenant -or [long]$verify.rows[0].mismatch_count -ne $expectedMismatch -or [long]$verify.rows[0].unexpected_count -ne 0){throw "商户$tenant 对账正确性断言失败。"}
   $stage.assertions+="商户$tenant：$expectedTenant 个结果，$expectedMismatch 个金额差错，其余匹配"
   $aggregateSql='SELECT f.store_id,COUNT_BIG(*) event_count,CONVERT(VARCHAR(40),SUM(CONVERT(DECIMAL(38,0),f.amount_cent))) receipt_cent FROM v_transaction_fact f WHERE f.merchant_id IN(SELECT merchant_id FROM merchant_member WHERE user_id=@actor AND status=''ACTIVE'') AND f.channel_occurred_at>=@start AND f.channel_occurred_at<@end AND f.recorded_at<=SYSUTCDATETIME() GROUP BY f.store_id ORDER BY f.store_id;'
   $aggregate=Measure-Sql "$size-statistics-tenant$tenant" $aggregateSql @{actor=$actor;start=[DateTime]'2026-01-01';end=[DateTime]'2026-01-30'} -Plans
   $aggregateCount=0L; $aggregateAmount=[decimal]0
   foreach($row in $aggregate.rows){$aggregateCount+=[long]$row.event_count; $aggregateAmount+=[decimal]$row.receipt_cent}
   $expectedTenantAmount=0L; for($n=1;$n -le $size;$n++){if(($n%2+1) -eq $tenant){$expectedTenantAmount+=100+$n%9900}}
   if($aggregateCount -ne $expectedTenant -or $aggregateAmount -ne $expectedTenantAmount){throw "商户$tenant 聚合数量或金额断言失败。"}
   $stage.measurements+=,$aggregate
   $stage.assertions+="商户$tenant 聚合数量与逐笔金额计算相等"
   $stage.measurements+=,(Measure-Sql "$size-page-tenant$tenant" 'SELECT f.id,f.event_key,f.amount_cent FROM v_transaction_fact f WHERE f.merchant_id IN(SELECT merchant_id FROM merchant_member WHERE user_id=@actor AND status=''ACTIVE'') ORDER BY f.channel_occurred_at DESC,f.id DESC OFFSET 100 ROWS FETCH NEXT 20 ROWS ONLY;' @{actor=$actor} -Plans)
  }
  $caseCount=[long](Invoke-Scalar 'SELECT COUNT_BIG(*) FROM exception_task')
  $deadline=[DateTime]::UtcNow.AddSeconds($FulltextWaitSeconds)
  $indexed=0L
  do {
   $indexed=[long](Invoke-Scalar "SELECT COALESCE(OBJECTPROPERTYEX(OBJECT_ID('dbo.exception_task'),'TableFulltextItemCount'),0)")
   $pending=[long](Invoke-Scalar "SELECT COALESCE(OBJECTPROPERTYEX(OBJECT_ID('dbo.exception_task'),'TableFulltextPendingChanges'),0)")
   $population=[int](Invoke-Scalar "SELECT COALESCE(FULLTEXTCATALOGPROPERTY('ReconisCases','PopulateStatus'),-1)")
   if($indexed -eq $caseCount -and $pending -eq 0 -and $population -eq 0){break}
   Start-Sleep -Milliseconds 500
  } while([DateTime]::UtcNow -lt $deadline)
  if($indexed -ne $caseCount -or $pending -ne 0 -or $population -ne 0){throw "全文索引在期限内未就绪：$indexed/$caseCount，待处理$pending，填充状态$population。"}
  foreach($queryCase in @(@{tenant=1;label='first'},@{tenant=1;label='repeat'},@{tenant=2;label='other-tenant'})) {
   $tenant=$queryCase.tenant
   $actor=[long](Invoke-Scalar "SELECT id FROM app_user WHERE username='perf_owner$tenant'")
   $merchant=[long](Invoke-Scalar "SELECT id FROM merchant WHERE merchant_code='PERF$tenant'")
   $searchSql=@'
SELECT TOP 30 c.id,c.case_no,c.merchant_id,c.classification,ft.[RANK] relevance
 FROM FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),@q,LANGUAGE 2052) ft
 JOIN v_case c ON c.id=ft.[KEY]
 WHERE c.merchant_id IN(SELECT merchant_id FROM merchant_member WHERE user_id=@actor AND status='ACTIVE')
 ORDER BY ft.[RANK] DESC,c.id DESC;
'@
   $search=Measure-Sql "$size-fulltext-$($queryCase.label)" $searchSql @{q='金额';actor=$actor} -Plans
   if($search.rowCount -lt 1){throw '中文全文检索未返回应有案例。'}
   foreach($row in $search.rows){if([long]$row.merchant_id -ne $merchant){throw '全文检索返回跨商户案例。'}}
   $stage.measurements+=,$search
  }
  $fulltextCount=Measure-Sql "$size-fulltext-count" "SELECT COUNT_BIG(*) matched_count FROM FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),N'金额',LANGUAGE 2052) ft;"
  if([long]$fulltextCount.rows[0].matched_count -ne $caseCount){throw '全文索引命中数与全部金额差错工单不一致。'}
  $stage.measurements+=,$fulltextCount
  $stage.assertions+="全文命中全部$caseCount 张金额差错工单，两个商户均无越权结果"
  $stage.status='PASSED'; $previous=$size
  $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
 }
 $report.status='PASSED'
} catch {
 $report.status='FAILED'; $report.error=$_.Exception.Message
 throw
} finally {
 $connection.remove_InfoMessage($handler); $connection.Dispose()
 if($creationAttempted) {
  $cleanup=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
  try {
   # This identifier is generated above, never supplied by the caller. No database enumeration/deletion.
   if($database -notmatch '^ReconisPerformance_[a-f0-9]{32}$'){throw '临时数据库名称校验失败。'}
   $cleanup.Open(); $cmd=$cleanup.CreateCommand(); $cmd.CommandTimeout=30
   $cmd.CommandText="IF DB_ID(@name) IS NOT NULL BEGIN DECLARE @sql NVARCHAR(MAX)=N'ALTER DATABASE '+QUOTENAME(@name)+N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE '+QUOTENAME(@name)+N';'; EXEC sys.sp_executesql @sql; END;"
   [void]$cmd.Parameters.AddWithValue('@name',$database); [void]$cmd.ExecuteNonQuery(); $cmd.Dispose(); $report.cleanup='COMPLETED'
  } catch { $report.cleanup='FAILED'; $report.cleanupError=$_.Exception.Message; $report.status='FAILED'; Write-Warning "仅此随机测试库需要检查：$database。$($_.Exception.Message)" }
  finally {$cleanup.Dispose()}
 } else {$report.cleanup='NOT_NEEDED'}
 $report.finishedAt=[DateTimeOffset]::Now.ToString('o')
 $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
 Write-Host "性能记录：$(Join-Path $output 'result.json')；状态：$($report.status)；清理：$($report.cleanup)"
}
if($report.status -ne 'PASSED'){throw '性能基准或清理未全部完成，请查看记录。'}
