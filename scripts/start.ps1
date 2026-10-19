#Requires -Version 7.4
param([ValidateSet('DEMO','ALIPAY_SANDBOX')][string]$Environment='DEMO',[switch]$Dev)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$config=Join-Path $root "private\$Environment.env.ps1"
if(-not(Test-Path -LiteralPath $config)){throw "未找到 $Environment 本地配置，先运行 scripts\initialize.ps1。"}
. $config
$jdk=Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME\bin;$env:PATH"}
if($Dev){Write-Host '另开终端在frontend目录运行 pnpm dev；API默认8080。'}else{Write-Host '打开 http://localhost:8080；按 Ctrl+C 停止。'}
Push-Location "$root\backend"
try{java -jar target\reconis-server-1.0.0.jar}finally{Pop-Location}
