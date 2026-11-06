$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$metadata=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'schema-summary.json') -Raw | ConvertFrom-Json
$sources=@{}
Get-ChildItem -LiteralPath (Join-Path $root 'database') -Filter '*.sql' -File | ForEach-Object {
 $content=Get-Content -LiteralPath $_.FullName -Raw
 foreach($batch in [regex]::Split($content,'(?im)^GO\s*$')) {
  if($batch -match '(?is)^\s*CREATE OR ALTER (VIEW|FUNCTION|PROCEDURE|TRIGGER) dbo\.([a-z_]+)') {
   $sources[$Matches[2]]=($batch.Trim() -replace '(?i)CREATE OR ALTER','CREATE' -replace '\s+',' ').ToLowerInvariant()
  }
 }
}
$results=[ordered]@{captured_at=(Get-Date).ToString('o');module_comparison=@();checks=@()}
foreach($db in $metadata.databases){
 $matched=@();$different=@()
 foreach($module in $db.modules){
  $actual=($module.definition.Trim() -replace '(?i)CREATE OR ALTER','CREATE' -replace '\s+',' ').ToLowerInvariant()
  if($sources[$module.name] -eq $actual){$matched+=$module.name}else{$different+=$module.name}
 }
 $results.module_comparison+=,[ordered]@{database=$db.name;matched=$matched;different=$different;method='Compare CREATE-normalized, whitespace-normalized definitions against 003 and 004 SQL files'}
 $connection=[System.Data.SqlClient.SqlConnection]::new("Data Source=127.0.0.1,1433;Initial Catalog=$($db.name);Integrated Security=True;Encrypt=True;TrustServerCertificate=True;Connect Timeout=10")
 try{
  $connection.Open();$q=$connection.CreateCommand();$q.CommandTimeout=30;$q.CommandText=@'
SELECT DB_NAME() AS database_name,
 (SELECT snapshot_isolation_state_desc FROM sys.databases WHERE database_id=DB_ID()) AS snapshot_isolation,
 (SELECT is_read_committed_snapshot_on FROM sys.databases WHERE database_id=DB_ID()) AS read_committed_snapshot,
 (SELECT COUNT(*) FROM sys.foreign_keys WHERE is_disabled=1 OR is_not_trusted=1) AS untrusted_or_disabled_foreign_keys,
 (SELECT COUNT(*) FROM sys.check_constraints WHERE is_disabled=1 OR is_not_trusted=1) AS untrusted_or_disabled_checks,
 (SELECT COUNT(*) FROM sys.triggers WHERE parent_class=1 AND is_disabled=1) AS disabled_triggers,
 (SELECT COUNT(*) FROM sys.security_policies) AS row_level_security_policies,
 (SELECT COUNT(*) FROM payment_attempt p WHERE p.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM transaction_entry e WHERE e.payment_id=p.id)) AS successful_payments_without_entry,
 (SELECT COUNT(*) FROM refund_request r WHERE r.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM transaction_entry e WHERE e.refund_id=r.id)) AS successful_refunds_without_entry,
 (SELECT COUNT(*) FROM transaction_entry e JOIN refund_request r ON r.id=e.refund_id WHERE e.amount_cent<>r.amount_cent) AS refund_entry_amount_conflicts,
 (SELECT COUNT(*) FROM transaction_entry e JOIN payment_attempt p ON p.id=e.payment_id JOIN v_order o ON o.id=p.order_id WHERE e.amount_cent<>o.amount_cent) AS receipt_order_amount_conflicts,
 (SELECT COUNT(*) FROM payment_attempt p WHERE dbo.fn_refundable_amount(p.id)<0) AS negative_refundable_amounts,
 (SELECT COUNT(*) FROM exception_task t JOIN reconciliation_result r ON r.id=t.origin_result_id JOIN reconciliation_result latest ON latest.id=t.latest_result_id JOIN reconciliation_batch ob ON ob.id=r.batch_id JOIN reconciliation_batch lb ON lb.id=latest.batch_id WHERE r.source_event_key<>latest.source_event_key OR ob.scope_type<>lb.scope_type OR ISNULL(ob.merchant_id,-1)<>ISNULL(lb.merchant_id,-1)) AS case_evidence_scope_conflicts
FOR JSON PATH,WITHOUT_ARRAY_WRAPPER
'@
  $results.checks+=,($q.ExecuteScalar()|ConvertFrom-Json)
 }finally{$connection.Dispose()}
}
$results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'database-checks.json') -Encoding utf8
$results | ConvertTo-Json -Depth 8
