$ErrorActionPreference='Stop'
$metadata=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'schema-summary.json') -Raw | ConvertFrom-Json
$db=$metadata.databases[0]
$rows=@()
foreach($table in $db.tables){
 foreach($column in $table.columns){
  $type=$column.type_name.ToUpperInvariant()
  if($type -in @('VARCHAR','CHAR','VARBINARY','BINARY','NVARCHAR','NCHAR')){
   $length=$column.max_length
   if($length -lt 0){$length='MAX'}elseif($type -in @('NVARCHAR','NCHAR')){$length=$length/2}
   $type+="($length)"
  }elseif($type -in @('DATETIME2','DATETIMEOFFSET','TIME')){$type+="($($column.scale))"}
  elseif($type -in @('DECIMAL','NUMERIC')){$type+="($($column.precision),$($column.scale))"}
  if($type -eq 'TIMESTAMP'){$type='ROWVERSION (BINARY(8))'}
  $keys=@()
  if($column.is_primary_key){$keys+='PK'}
  if($column.is_identity){$keys+='IDENTITY'}
  foreach($index in $table.indexes){
   if($index.is_unique -and -not $index.is_primary_key -and @($index.columns.name) -contains $column.name){
    $keyColumns=($index.columns|Where-Object {-not $_.is_included_column}|Sort-Object key_ordinal|ForEach-Object name) -join ','
    $keys+="UNIQUE($keyColumns)"+$(if($index.filter_definition){" WHERE $($index.filter_definition)"})
   }
  }
  foreach($fk in $db.foreign_keys|Where-Object from_table -eq $table.name){
   foreach($map in $fk.columns){if($map.from_column -eq $column.name){$keys+="FK $($fk.to_table).$($map.to_column)"}}
  }
  $rows+=,[pscustomobject]@{table=$table.name;ordinal=$column.ordinal;column=$column.name;sql_type=$type;nullable=$column.is_nullable;keys=($keys -join '; ');default=$column.default_definition}
 }
}
$rows | Export-Csv -LiteralPath (Join-Path $PSScriptRoot 'schema-fields.csv') -NoTypeInformation -Encoding utf8BOM
$lines=[Collections.Generic.List[string]]::new()
$lines.Add('# 最终字段字典')
$lines.Add('')
$lines.Add('来自 2026 年 11 月 4 日 ReconisDemo 的 sys 元数据，共 15 表 207 列。nvarchar/nchar 长度按字符展示，ROWVERSION 为自动维护的 8 字节版本标记。组合唯一约束和筛选唯一索引在涉及列中重复注明，不能将其误读为每列分别唯一。')
foreach($table in $db.tables){
 $lines.Add('');$lines.Add("## $($table.name)");$lines.Add('')
 $lines.Add('| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |');$lines.Add('|---|---|---|---|---|')
 foreach($row in $rows|Where-Object table -eq $table.name){
  $keys=$row.keys.Replace('|','\|');$default=[string]$row.default
  if(-not $keys){$keys='—'};if(-not $default){$default='—'}
  $lines.Add("| $($row.column) | $($row.sql_type) | $(if($row.nullable){'是'}else{'否'}) | $keys | $default |")
 }
 if($table.checks.Count){
  $lines.Add('');$lines.Add('表级 CHECK：')
  foreach($check in $table.checks){$lines.Add('');$lines.Add('```sql');$lines.Add($check.definition);$lines.Add('```')}
 }
}
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'database-dictionary.md'),$lines,[Text.UTF8Encoding]::new($false))
"Exported $($rows.Count) columns."
