#Requires -Version 7.4
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$jdk=Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME\bin;$env:PATH"}
Push-Location "$root\frontend"
try{pnpm build;if($LASTEXITCODE){throw '前端构建失败'}}finally{Pop-Location}
$static=Join-Path $root 'backend\src\main\resources\static'
New-Item -ItemType Directory -Path $static -Force | Out-Null
Copy-Item -Path "$root\frontend\dist\*" -Destination $static -Recurse -Force
Push-Location "$root\backend"
try{& "$root\.tools\apache-maven-3.9.11\bin\mvn.cmd" -B package '-DskipTests';if($LASTEXITCODE){throw '后端构建失败'}}finally{Pop-Location}
Write-Host '构建完成：backend\target\reconis-server-1.0.0.jar（含前端）'
