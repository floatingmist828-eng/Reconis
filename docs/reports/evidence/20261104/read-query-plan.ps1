$ErrorActionPreference='Stop'
$connection=[System.Data.SqlClient.SqlConnection]::new('Data Source=127.0.0.1,1433;Initial Catalog=ReconisDemo;Integrated Security=True;Encrypt=True;TrustServerCertificate=True;Connect Timeout=10')
try{
 $connection.Open();$command=$connection.CreateCommand();$command.CommandTimeout=15;$command.CommandText='SET SHOWPLAN_XML ON';[void]$command.ExecuteNonQuery()
 $queries=[ordered]@{
  payment_order='SELECT id,status,is_primary FROM dbo.payment_attempt WHERE order_id=1'
  pending_refund="SELECT id,payment_id,amount_cent FROM dbo.refund_request WHERE status='UNKNOWN' AND next_query_at<CONVERT(datetime2(3),'2026-11-05T00:00:00')"
 }
 $summary=@()
 foreach($name in $queries.Keys){
  $command.CommandText=$queries[$name];$plan=[string]$command.ExecuteScalar()
  [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$name.sqlplan"),$plan,[Text.UTF8Encoding]::new($false))
  [xml]$xml=$plan;$objects=@($xml.SelectNodes("//*[local-name()='Object']")|ForEach-Object {$_.Index}|Select-Object -Unique)
  $operators=@($xml.SelectNodes("//*[local-name()='RelOp']")|ForEach-Object {$_.PhysicalOp}|Select-Object -Unique)
  $summary+=,[ordered]@{name=$name;database='ReconisDemo';captured_at=(Get-Date).ToString('o');kind='estimated plan only; query not executed';sql=$queries[$name];indexes=$objects;operators=$operators}
 }
 $command.CommandText='SET SHOWPLAN_XML OFF';[void]$command.ExecuteNonQuery()
 $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'query-plan-summary.json') -Encoding utf8
 $summary | ConvertTo-Json -Depth 5
}finally{$connection.Dispose()}
