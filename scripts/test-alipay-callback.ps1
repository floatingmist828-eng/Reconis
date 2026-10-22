#Requires -Version 7.4
param(
 [Parameter(Mandatory=$true)][string]$CallbackUrl,
 [ValidateSet('ReconisDemo','ReconisSandbox')][string]$DatabaseName
)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$uri=$null
if(-not [Uri]::TryCreate($CallbackUrl,[UriKind]::Absolute,[ref]$uri)){
 throw 'CallbackUrl must be an absolute URL.'
}
if($uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -cne '/api/v1/callbacks/alipay'){
 throw 'Use the exact /api/v1/callbacks/alipay URL without credentials, query parameters or fragments.'
}
$local=$uri.Host -in @('localhost','127.0.0.1','[::1]','::1')
if($uri.Scheme -ne 'https' -and -not ($local -and $uri.Scheme -eq 'http')){
 throw 'Public callback probes require HTTPS. HTTP is permitted only for the explicitly named loopback host.'
}
if(-not $local -and $uri.Port -ne 443){throw 'Use the standard HTTPS public endpoint on port 443.'}
$probe='PROBE_'+[Guid]::NewGuid().ToString('N')
$parameters=[Collections.Generic.Dictionary[string,string]]::new()
$parameters['notify_id']=$probe
$parameters['notify_type']='trade_status_sync'
$parameters['out_trade_no']=$probe
$parameters['trade_no']=$probe
$parameters['app_id']='UNSIGNED_LOCAL_PROBE'
$parameters['seller_id']='UNSIGNED_LOCAL_PROBE'
$parameters['total_amount']='0.01'
$parameters['trade_status']='TRADE_SUCCESS'
$parameters['sign_type']='RSA2'
# Deliberately omit sign: this request must never be accepted as a channel notification.
$handler=[Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect=$false
$client=[Net.Http.HttpClient]::new($handler)
$client.Timeout=[TimeSpan]::FromSeconds(15)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Reconis-Callback-Probe/1.0')
$content=[Net.Http.FormUrlEncodedContent]::new($parameters)
$response=$null
$connection=$null
try {
 $response=$client.PostAsync($uri,$content).GetAwaiter().GetResult()
 $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
 $status=[int]$response.StatusCode
 if($status -ne 200 -or $body -cne 'failure' -or $response.Content.Headers.ContentType.MediaType -ne 'text/plain'){
  throw "Callback rejection contract failed: HTTP $status; expected exactly text/plain failure. No redirect was followed."
 }
 $databaseVerified=$false
 if($DatabaseName){
  # This optional check reads only the user's explicitly chosen local database and this probe id.
  $builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
  $builder['Data Source']='127.0.0.1,1433'
  $builder['Initial Catalog']=$DatabaseName
  $builder['Integrated Security']=$true
  $builder['Encrypt']=$true
  $builder['TrustServerCertificate']=$true
  $builder['Connect Timeout']=10
  $connection=[System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
  $connection.Open()
  $query=$connection.CreateCommand()
  $query.CommandTimeout=10
  $query.CommandText=@'
SELECT
 (SELECT COUNT_BIG(*) FROM payment_attempt WHERE out_trade_no=@probe) AS matching_payments,
 (SELECT COUNT_BIG(*) FROM transaction_entry e JOIN payment_attempt p ON p.id=e.payment_id WHERE p.out_trade_no=@probe) AS matching_receipts,
 (SELECT COUNT_BIG(*) FROM payment_notification WHERE notify_id=@probe AND payment_id IS NULL AND signature_valid=0 AND handling_result='REJECTED') AS rejected_notifications;
'@
  [void]$query.Parameters.AddWithValue('@probe',$probe)
  $reader=$query.ExecuteReader()
  try {
   if(-not $reader.Read() -or $reader.GetInt64(0) -ne 0 -or $reader.GetInt64(1) -ne 0 -or $reader.GetInt64(2) -ne 1){
    throw 'Local database verification failed: expected one rejected audit record, no associated payment or receipt.'
   }
   $databaseVerified=$true
  } finally {$reader.Dispose()}
 }
 $result=[ordered]@{
  verifiedAt=[DateTimeOffset]::Now.ToString('o')
  callbackUrl=$uri.AbsoluteUri
  probeId=$probe
  httpStatus=$status
  responseBody=$body
  rejectionContractVerified=$true
  localDatabaseVerified=$databaseVerified
  databaseName=$DatabaseName
  realAlipayNotificationVerified=$false
  scope='Unsigned negative probe only. Public reachability from Alipay and a real signed notification still require a new sandbox payment after notify URL configuration.'
 }
 $directory=Join-Path $repo 'artifacts/validation'
 [void][IO.Directory]::CreateDirectory($directory)
 $path=Join-Path $directory ("callback-probe-"+[DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff')+'.json')
 $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $path -Encoding utf8
 Write-Host 'Callback rejected the unsigned probe with HTTP 200 and exactly failure.'
 if($databaseVerified){Write-Host 'Local SQL verification: one rejected audit record; no payment or receipt created for the probe.'}
 else{Write-Host 'Database effects were not inspected. Supply DatabaseName for verification against the corresponding local SQL database.'}
 Write-Host "Evidence: $path"
 Write-Host 'This is not verification of a real Alipay notification.'
} finally {
 if($connection){$connection.Dispose()}
 if($response){$response.Dispose()}
 $content.Dispose()
 $client.Dispose()
 $handler.Dispose()
}
