#Requires -Version 5.1
param(
    [ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',
    [ValidateRange(1,65535)][int]$Port=8080,
    [switch]$Dev,
    [string]$JarFile,
    [string]$ReleaseId,
    [switch]$CheckOnly,
    [ValidateRange(32,8192)][int]$InitialHeapMb=128,
    [ValidateRange(128,8192)][int]$MaxHeapMb=512,
    [ValidateRange(1,64)][int]$ProcessorCount=4
)
$ErrorActionPreference='Stop'
if($InitialHeapMb -gt $MaxHeapMb){throw 'InitialHeapMb 不能大于 MaxHeapMb。'}
if(-not $PSBoundParameters.ContainsKey('Port') -and -not [string]::IsNullOrWhiteSpace($env:PORT)){
    $configuredPort=0
    if(-not [int]::TryParse($env:PORT,[ref]$configuredPort) -or $configuredPort -lt 1 -or $configuredPort -gt 65535){
        throw 'PORT 环境变量必须是 1–65535 的整数，也可以通过 -Port 参数指定端口。'
    }
    $Port=$configuredPort
}
if($JarFile -and $ReleaseId){throw 'JarFile 与 ReleaseId 只能指定其中一个。'}

function New-DatabasePreflightConnectionString {
    param([string]$JdbcUrl,[string]$Username,[string]$Password)
    if([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrEmpty($Password)){
        throw '数据库预检查缺少 DB_USER 或 DB_PASSWORD，请检查本地环境配置。'
    }
    if($JdbcUrl -notmatch '^jdbc:sqlserver://(?<endpoint>[^;]+)(?<properties>(?:;.*)?)$'){
        throw '无法解析 DB_URL：请使用 jdbc:sqlserver://主机:端口 或 主机\实例名，并指定 databaseName。'
    }
    $endpoint=$Matches.endpoint
    $properties=@{}
    foreach($entry in ($Matches.properties -split ';')){
        if([string]::IsNullOrWhiteSpace($entry)){continue}
        $separator=$entry.IndexOf('=')
        if($separator -lt 1){throw '无法解析 DB_URL 的属性，请检查分号和等号；预检查不支持含分号或花括号转义的属性值。'}
        $key=$entry.Substring(0,$separator).Trim()
        $value=$entry.Substring($separator+1).Trim()
        if($value.Contains('{') -or $value.Contains('}')){
            throw '数据库预检查暂不支持 DB_URL 中的花括号转义值，请使用普通主机、实例名和数据库名配置。'
        }
        $properties[$key]=$value
    }
    if($endpoint -notmatch '^(?<host>\[[0-9a-fA-F:.%]+\]|[^\\:;\s]+)(?:\\(?<instance>[^\\:;\s]+))?(?::(?<port>[0-9]{1,5}))?$'){
        throw '无法解析 DB_URL 的服务器地址，请明确使用主机:端口或主机\实例名。'
    }
    $server=$Matches.host
    if($Matches.port){
        $port=[int]$Matches.port
        if($port -lt 1 -or $port -gt 65535){throw 'DB_URL 的端口必须介于 1 和 65535 之间。'}
        # JDBC 在同时指定实例和端口时优先使用端口。
        $server+=",$port"
    }elseif($Matches.instance){$server+="\$($Matches.instance)"}
    foreach($key in @('serverName','portNumber','instanceName','user','userName','password','integratedSecurity')){
        if($properties.ContainsKey($key)){
            throw 'DB_URL 包含额外的服务器或身份配置，无法保证预检查与应用使用同一连接；请在地址中指定服务器，并用 DB_USER、DB_PASSWORD 配置 SQL 账号。'
        }
    }
    if($properties.ContainsKey('authentication') -and $properties.authentication -notin @('SqlPassword','NotSpecified')){
        throw '数据库预检查仅支持 DB_USER、DB_PASSWORD 对应的 SQL 账号认证。'
    }
    if([string]::IsNullOrWhiteSpace($properties.databaseName)){
        throw 'DB_URL 必须明确指定 databaseName，预检查不会选择默认数据库。'
    }
    foreach($key in @('encrypt','trustServerCertificate')){
        if($properties.ContainsKey($key) -and $properties[$key] -notin @('true','false')){
            throw '数据库预检查要求 encrypt 和 trustServerCertificate 使用 true 或 false。'
        }
    }
    try{
        $builder=[System.Data.SqlClient.SqlConnectionStringBuilder]::new()
        $builder['Data Source']=$server
        $builder['Initial Catalog']=$properties.databaseName
        $builder['User ID']=$Username
        $builder['Password']=$Password
        $builder['Encrypt']=(-not $properties.ContainsKey('encrypt') -or $properties.encrypt -eq 'true')
        $builder['TrustServerCertificate']=($properties.trustServerCertificate -eq 'true')
        $builder['Connect Timeout']=5
        $builder['ConnectRetryCount']=0
        $builder['Pooling']=$false
        $builder['Application Name']='Reconis startup check'
        return $builder.ConnectionString
    }catch{
        throw '无法创建数据库预检查连接，请检查 DB_URL、DB_USER 和 DB_PASSWORD 的配置格式。'
    }
}

function Test-DatabaseBeforeStartup {
    param([string]$ConnectionString)
    $connection=$null
    $command=$null
    try{
        $connection=[System.Data.SqlClient.SqlConnection]::new($ConnectionString)
        $connection.Open()
        $command=$connection.CreateCommand()
        $command.CommandText='SELECT 1'
        $command.CommandTimeout=5
        if([int]$command.ExecuteScalar() -ne 1){throw 'Unexpected database response'}
    }catch{
        throw '数据库预检查失败，应用尚未启动。请检查 SQL Server 服务、指定数据库和运行账号是否可用；连接与查询的超时各为 5 秒。'
    }finally{
        if($null -ne $command){$command.Dispose()}
        if($null -ne $connection){$connection.Dispose()}
    }
}

$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'release-common.ps1')
$config=Join-Path $root "private\$Environment.env.ps1"
if(-not(Test-Path -LiteralPath $config)){throw "未找到 $Environment 本地配置，先运行 scripts\initialize.ps1。"}
. $config
# Explicit -Port (or the caller's PORT) is resolved before configuration and must not be overwritten by it.
$env:PORT=[string]$Port
if($Environment -eq 'ALIPAY_SANDBOX'){
    foreach($keyVariable in @('ALIPAY_PRIVATE_KEY_FILE','ALIPAY_PUBLIC_KEY_FILE')){
        $keyPath=[Environment]::GetEnvironmentVariable($keyVariable)
        if(-not [string]::IsNullOrWhiteSpace($keyPath) -and -not (Test-Path -LiteralPath $keyPath -PathType Leaf)){
            throw "$keyVariable 指定的密钥文件不存在。请检查配置路径；Windows PowerShell 5.1 下含中文路径的配置文件须保存为 UTF-8 BOM 编码。"
        }
    }
}
$release=Resolve-ReconisServerJar -Root $root -JarFile $JarFile -ReleaseId $ReleaseId
$serverJar=$release.JarPath
if($release.Source -eq 'release'){Write-Host "统一运行版本：$($release.ReleaseId)（$($release.Version)）"}
elseif($release.Source -eq 'override'){Write-Host '使用显式程序包覆盖配置，未使用统一默认版本。'}
else{Write-Host '使用兼容程序包；运行 scripts\build.ps1 后可切换到统一版本。'}
Write-Host "服务程序：$serverJar"
Write-Host "SHA256：$($release.Sha256)"
Test-DatabaseBeforeStartup -ConnectionString (New-DatabasePreflightConnectionString -JdbcUrl $env:DB_URL -Username $env:DB_USER -Password $env:DB_PASSWORD)
Write-Host '数据库连接与查询检查通过。'
if($CheckOnly){
    [pscustomobject]@{Environment=$Environment;Port=$Port;ReleaseId=$release.ReleaseId;Source=$release.Source;JarPath=$serverJar;Sha256=$release.Sha256;Database='UP'}
    return
}
$jdk=Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME\bin;$env:PATH"}
if($Dev){Write-Host "另开终端在frontend目录运行 pnpm dev；API端口为 $Port，前端代理目标须与此一致。"}else{Write-Host "打开 http://localhost:$Port；按 Ctrl+C 停止。"}
Push-Location "$root\backend"
try{
    java "-Xms${InitialHeapMb}m" "-Xmx${MaxHeapMb}m" "-XX:ActiveProcessorCount=$ProcessorCount" -jar $serverJar
    if($LASTEXITCODE){throw "Reconis 未正常运行（退出码 $LASTEXITCODE），请检查上面的启动日志。"}
}finally{Pop-Location}
