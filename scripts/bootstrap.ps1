#Requires -Version 7.4
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$tools=Join-Path $root '.tools'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
if(-not(Get-Command java -ErrorAction SilentlyContinue) -and -not(Test-Path -LiteralPath "$tools\java")){
 Write-Host '下载 Microsoft OpenJDK 21（便携版）...'
 Invoke-WebRequest 'https://aka.ms/download-jdk/microsoft-jdk-21.0.8-windows-x64.zip' -OutFile "$tools\jdk.zip"
 Expand-Archive -LiteralPath "$tools\jdk.zip" -DestinationPath "$tools\java" -Force
}
$jdk=Get-ChildItem -LiteralPath "$tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME\bin;$env:PATH"}
if(-not(Test-Path -LiteralPath "$tools\apache-maven-3.9.11")){
 $url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
 Invoke-WebRequest $url -OutFile "$tools\maven.zip"
 $expected=(Invoke-WebRequest "$url.sha512").Content.Trim().Split(' ')[0]
 if((Get-FileHash -LiteralPath "$tools\maven.zip" -Algorithm SHA512).Hash -ne $expected){throw 'Maven 文件校验未通过。'}
 Expand-Archive -LiteralPath "$tools\maven.zip" -DestinationPath $tools -Force
}
if(-not(Get-Command node -ErrorAction SilentlyContinue)){throw '请安装 Node.js 24 LTS，再运行本脚本。'}
if((node -p "process.versions.node.split('.')[0]") -ne '24'){throw '项目固定使用 Node.js 24 LTS。'}
if(-not(Get-Command pnpm -ErrorAction SilentlyContinue)){npm install --global pnpm@11.25.0;if($LASTEXITCODE){throw 'pnpm安装失败'}}
Push-Location (Join-Path $root 'frontend')
try{pnpm install --frozen-lockfile;if($LASTEXITCODE){throw '前端依赖安装失败'}}finally{Pop-Location}
Write-Host '构建工具和依赖已就绪，运行 scripts\build.ps1。'
