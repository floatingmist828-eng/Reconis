$ErrorActionPreference='Stop'
$queries=[ordered]@{
 server=@'
SELECT @@VERSION AS version_text, CONVERT(nvarchar(128),SERVERPROPERTY('ProductVersion')) AS product_version,
 CONVERT(nvarchar(128),SERVERPROPERTY('ProductLevel')) AS product_level, CONVERT(nvarchar(128),SERVERPROPERTY('Edition')) AS edition,
 CONVERT(int,FULLTEXTSERVICEPROPERTY('IsFullTextInstalled')) AS fulltext_installed, DB_NAME() AS database_name,
 DATABASEPROPERTYEX(DB_NAME(),'Collation') AS collation_name,
 (SELECT compatibility_level FROM sys.databases WHERE database_id=DB_ID()) AS compatibility_level
FOR JSON PATH, WITHOUT_ARRAY_WRAPPER
'@
 tables=@'
SELECT t.name, s.name AS schema_name,
 (SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id=t.object_id AND p.index_id IN (0,1)) AS row_count,
 JSON_QUERY((SELECT c.column_id AS ordinal,c.name,ty.name AS type_name,c.max_length,c.precision,c.scale,c.is_nullable,c.is_identity,c.is_computed,
  dc.definition AS default_definition,
  (SELECT STRING_AGG(fk.name,',') FROM sys.foreign_key_columns fc JOIN sys.foreign_keys fk ON fk.object_id=fc.constraint_object_id WHERE fc.parent_object_id=c.object_id AND fc.parent_column_id=c.column_id) AS foreign_key_names,
  CONVERT(bit,CASE WHEN EXISTS(SELECT 1 FROM sys.index_columns ic JOIN sys.indexes i ON i.object_id=ic.object_id AND i.index_id=ic.index_id WHERE ic.object_id=c.object_id AND ic.column_id=c.column_id AND i.is_primary_key=1) THEN 1 ELSE 0 END) AS is_primary_key
 FROM sys.columns c JOIN sys.types ty ON ty.user_type_id=c.user_type_id LEFT JOIN sys.default_constraints dc ON dc.object_id=c.default_object_id WHERE c.object_id=t.object_id ORDER BY c.column_id FOR JSON PATH)) AS columns,
 JSON_QUERY((SELECT cc.name,cc.definition,cc.is_disabled,cc.is_not_trusted FROM sys.check_constraints cc WHERE cc.parent_object_id=t.object_id FOR JSON PATH)) AS checks,
 JSON_QUERY((SELECT i.name,i.type_desc,i.is_unique,i.is_primary_key,i.is_unique_constraint,i.filter_definition,i.is_disabled,
  JSON_QUERY((SELECT c.name,ic.key_ordinal,ic.is_descending_key,ic.is_included_column FROM sys.index_columns ic JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id WHERE ic.object_id=i.object_id AND ic.index_id=i.index_id ORDER BY ic.index_column_id FOR JSON PATH)) AS columns
 FROM sys.indexes i WHERE i.object_id=t.object_id AND i.index_id>0 ORDER BY i.index_id FOR JSON PATH)) AS indexes
FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE t.is_ms_shipped=0 ORDER BY t.name FOR JSON PATH
'@
 foreign_keys=@'
SELECT fk.name,OBJECT_NAME(fk.parent_object_id) AS from_table,OBJECT_NAME(fk.referenced_object_id) AS to_table,
 fk.delete_referential_action_desc AS on_delete,fk.update_referential_action_desc AS on_update,fk.is_disabled,fk.is_not_trusted,
 JSON_QUERY((SELECT pc.name AS from_column,rc.name AS to_column FROM sys.foreign_key_columns fc
 JOIN sys.columns pc ON pc.object_id=fc.parent_object_id AND pc.column_id=fc.parent_column_id
 JOIN sys.columns rc ON rc.object_id=fc.referenced_object_id AND rc.column_id=fc.referenced_column_id
 WHERE fc.constraint_object_id=fk.object_id ORDER BY fc.constraint_column_id FOR JSON PATH)) AS columns
FROM sys.foreign_keys fk ORDER BY from_table,fk.name FOR JSON PATH
'@
 modules=@'
SELECT o.name,o.type_desc,OBJECT_NAME(o.parent_object_id) AS parent_table,m.definition,
 CASE WHEN tr.object_id IS NOT NULL THEN tr.is_disabled ELSE NULL END AS trigger_disabled
FROM sys.objects o JOIN sys.sql_modules m ON m.object_id=o.object_id LEFT JOIN sys.triggers tr ON tr.object_id=o.object_id
WHERE o.is_ms_shipped=0 ORDER BY o.type_desc,o.name FOR JSON PATH
'@
 fulltext=@'
SELECT OBJECT_NAME(fi.object_id) AS table_name,fc.name AS catalog_name,fi.is_enabled,fi.change_tracking_state_desc,fi.has_crawl_completed,
 fi.crawl_type_desc,fi.crawl_start_date,fi.crawl_end_date,
 FULLTEXTCATALOGPROPERTY(fc.name,'PopulateStatus') AS populate_status,
 JSON_QUERY((SELECT c.name,fic.language_id FROM sys.fulltext_index_columns fic JOIN sys.columns c ON c.object_id=fic.object_id AND c.column_id=fic.column_id WHERE fic.object_id=fi.object_id FOR JSON PATH)) AS columns
FROM sys.fulltext_indexes fi JOIN sys.fulltext_catalogs fc ON fc.fulltext_catalog_id=fi.fulltext_catalog_id FOR JSON PATH
'@
 roles=@'
SELECT dp.name,dp.type_desc,
 JSON_QUERY((SELECT pe.state_desc,pe.permission_name,pe.class_desc,
 CASE pe.class WHEN 1 THEN OBJECT_NAME(pe.major_id) WHEN 3 THEN SCHEMA_NAME(pe.major_id) WHEN 23 THEN (SELECT name FROM sys.fulltext_catalogs WHERE fulltext_catalog_id=pe.major_id) ELSE NULL END AS target
 FROM sys.database_permissions pe WHERE pe.grantee_principal_id=dp.principal_id FOR JSON PATH)) AS permissions
FROM sys.database_principals dp WHERE dp.name IN ('reconis_runtime','reconis_auditor') ORDER BY dp.name FOR JSON PATH
'@
}
$report=[ordered]@{captured_at=(Get-Date).ToString('o');source='Read-only SQL Server sys metadata via Windows integrated authentication';databases=@()}
foreach($dbName in @('ReconisDemo','ReconisSandbox')){
 $connection=[System.Data.SqlClient.SqlConnection]::new("Data Source=127.0.0.1,1433;Initial Catalog=$dbName;Integrated Security=True;Encrypt=True;TrustServerCertificate=True;Connect Timeout=10;Application Name=Reconis report metadata read")
 try{
  $connection.Open(); $db=[ordered]@{name=$dbName}
  foreach($key in $queries.Keys){
   $command=$connection.CreateCommand();$command.CommandTimeout=30;$command.CommandText=$queries[$key]
   $reader=$command.ExecuteReader();$json=[Text.StringBuilder]::new()
   try{while($reader.Read()){[void]$json.Append($reader.GetString(0))}}finally{$reader.Dispose();$command.Dispose()}
   $db[$key]=ConvertFrom-Json -InputObject $json.ToString() -Depth 50
  }
  $report.databases+=,$db
 }finally{$connection.Dispose()}
}
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'schema-summary.json') -Encoding utf8
$report.databases | ForEach-Object {[pscustomobject]@{database=$_.name;version=$_.server.product_version;tables=$_.tables.Count;foreign_keys=$_.foreign_keys.Count;views=@($_.modules|Where-Object type_desc -eq 'VIEW').Count;procedures=@($_.modules|Where-Object type_desc -eq 'SQL_STORED_PROCEDURE').Count;functions=@($_.modules|Where-Object type_desc -eq 'SQL_SCALAR_FUNCTION').Count;triggers=@($_.modules|Where-Object type_desc -eq 'SQL_TRIGGER').Count;indexes=($_.tables|ForEach-Object {$_.indexes.Count}|Measure-Object -Sum).Sum;fulltext_indexes=@($_.fulltext).Count}} | ConvertTo-Json
