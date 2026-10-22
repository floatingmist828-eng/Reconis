#Requires -Version 7.4
param(
 [switch]$SkipTests,
 [switch]$DatabaseTests,
 [string]$MavenSettings,
 [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$')][string]$ReleaseId=(Get-Date -Format 'yyyyMMdd-HHmmssfff')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6),
 [switch]$NoSelect
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if($SkipTests -and $DatabaseTests){throw 'DatabaseTests 与 SkipTests 不能同时使用。'}
if($MavenSettings){
 $MavenSettings=[IO.Path]::GetFullPath($MavenSettings)
 if(-not(Test-Path -LiteralPath $MavenSettings -PathType Leaf)){throw 'MavenSettings 指定的文件不存在。'}
}
. (Join-Path $PSScriptRoot 'release-common.ps1')
$buildRoot=Join-Path $root "artifacts\build\$ReleaseId"
$releaseRoot=Join-Path $root "artifacts\releases\$ReleaseId"
if((Test-Path -LiteralPath $buildRoot) -or (Test-Path -LiteralPath $releaseRoot)){
 throw '此版本的构建或发布目录已经存在；请使用新的 ReleaseId，避免覆盖已有版本。'
}
New-Item -ItemType Directory -Path (Join-Path $buildRoot 'backend') -Force | Out-Null
foreach($name in @('.mvn','mvnw','mvnw.cmd','pom.xml')){
 Copy-Item -LiteralPath (Join-Path $root "backend\$name") -Destination (Join-Path $buildRoot 'backend') -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $root 'backend\src') -Destination (Join-Path $buildRoot 'backend') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $root 'database') -Destination $buildRoot -Recurse -Force
# Keep a small source snapshot with the package. Dependencies, credentials and business data are never copied.
$frontendSnapshot=Join-Path $buildRoot 'frontend'
New-Item -ItemType Directory -Path $frontendSnapshot -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $root 'frontend') -Force | Where-Object {$_.Name -notin @('node_modules','dist','.vite','playwright-report','test-results') -and $_.Name -notlike '.env*'} | ForEach-Object {
 Copy-Item -LiteralPath $_.FullName -Destination $frontendSnapshot -Recurse -Force
}
$sourceFiles=@(Get-ChildItem -LiteralPath (Join-Path $buildRoot 'backend'),(Join-Path $buildRoot 'database'),$frontendSnapshot -File -Recurse | Where-Object {$_.FullName -notlike '*\src\main\resources\static\*' -and $_.FullName -ne (Join-Path $buildRoot 'backend\src\main\resources\reconis-build.properties')} | ForEach-Object {
 [ordered]@{path=[IO.Path]::GetRelativePath($buildRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
} | Sort-Object {$_.path})
Write-ReconisJsonAtomic -Path (Join-Path $buildRoot 'source-files.json') -Value $sourceFiles
$sourceFilesSha256=(Get-FileHash -LiteralPath (Join-Path $buildRoot 'source-files.json') -Algorithm SHA256).Hash
$buildProperties=Join-Path $buildRoot 'backend\src\main\resources\reconis-build.properties'
[IO.File]::WriteAllText($buildProperties,"releaseId=$ReleaseId`nbuildSourceSha256=$sourceFilesSha256`n",[Text.UTF8Encoding]::new($false))
$jdk=Get-ChildItem -LiteralPath "$root\.tools\java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME\bin;$env:PATH"}
elseif(Get-Command java -ErrorAction SilentlyContinue){$env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)}
# Build the snapshotted frontend with the existing pinned dependencies. Vite and TypeScript resolve
# through this link; source edits made after the snapshot do not change the release being built.
if(-not(Test-Path -LiteralPath (Join-Path $root 'frontend\node_modules'))){
 Push-Location (Join-Path $root 'frontend')
 try{pnpm install --frozen-lockfile;if($LASTEXITCODE){throw '前端依赖安装失败'}}finally{Pop-Location}
}
New-Item -ItemType Junction -Path (Join-Path $frontendSnapshot 'node_modules') -Target (Join-Path $root 'frontend\node_modules') | Out-Null
Push-Location $frontendSnapshot
try{
 $package=Get-Content -LiteralPath 'package.json' -Raw | ConvertFrom-Json
 if(-not $SkipTests -and $package.scripts.test){pnpm test;if($LASTEXITCODE){throw '前端回归测试失败'}}
 pnpm build;if($LASTEXITCODE){throw '前端构建失败'}
}finally{Pop-Location}
$static=Join-Path $buildRoot 'backend\src\main\resources\static'
$expectedStatic=[IO.Path]::GetFullPath((Join-Path $buildRoot 'backend\src\main\resources\static'))
if([IO.Path]::GetFullPath($static) -ne $expectedStatic -or -not $expectedStatic.StartsWith([IO.Path]::GetFullPath($buildRoot)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
 throw '静态构建目录校验失败'
}
if(Test-Path -LiteralPath $static){Remove-Item -LiteralPath $static -Recurse -Force}
New-Item -ItemType Directory -Path $static -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $frontendSnapshot 'dist') -Force | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $static -Recurse -Force}
if($DatabaseTests){
 $databaseTestArgs=@{BackendDirectory=(Join-Path $buildRoot 'backend');AllTests=$true;Package=$true}
 if($MavenSettings){$databaseTestArgs.MavenSettings=$MavenSettings}
 & (Join-Path $PSScriptRoot 'test-database.ps1') @databaseTestArgs
}else{
 Push-Location (Join-Path $buildRoot 'backend')
 try{
  $mavenArgs=@('-B','-ntp','clean','package')
  if($SkipTests){$mavenArgs+='-DskipTests'}
  if($MavenSettings){$mavenArgs+=@('-s',$MavenSettings)}
  & (Join-Path $buildRoot 'backend\mvnw.cmd') @mavenArgs
  if($LASTEXITCODE){throw '后端构建或测试失败'}
 }finally{Pop-Location}
}
[xml]$pom=Get-Content -LiteralPath (Join-Path $buildRoot 'backend\pom.xml') -Raw -Encoding UTF8
$version=[string]$pom.project.version
$jar=Join-Path $buildRoot "backend\target\reconis-server-$version.jar"
if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){throw '构建没有生成预期的 Spring Boot 程序包。'}
New-Item -ItemType Directory -Path $releaseRoot | Out-Null
$publishedJar=Join-Path $releaseRoot 'reconis-server.jar'
Copy-Item -LiteralPath $jar -Destination $publishedJar
Copy-Item -LiteralPath (Join-Path $buildRoot 'source-files.json') -Destination $releaseRoot
$tests=[ordered]@{requested=(-not $SkipTests);databaseRequested=[bool]$DatabaseTests;tests=0;failures=0;errors=0;skipped=0;frontend=if($SkipTests){'SKIPPED'}elseif($package.scripts.test){'PASSED'}else{'NOT_CONFIGURED'}}
Get-ChildItem -LiteralPath (Join-Path $buildRoot 'backend\target\surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
 [xml]$report=Get-Content -LiteralPath $_.FullName -Raw
 foreach($key in @('tests','failures','errors','skipped')){$tests[$key]+=[int]$report.testsuite.$key}
}
$manifest=[ordered]@{
 schemaVersion=1;releaseId=$ReleaseId;version=$version;createdUtc=[DateTime]::UtcNow.ToString('o')
 jarFile='reconis-server.jar';jarSha256=(Get-FileHash -LiteralPath $publishedJar -Algorithm SHA256).Hash
 sourceFilesSha256=$sourceFilesSha256
 buildDirectory=[IO.Path]::GetRelativePath($root,$buildRoot).Replace('\','/');tests=$tests
}
Write-ReconisJsonAtomic -Path (Join-Path $releaseRoot 'manifest.json') -Value $manifest
if(-not $NoSelect){[void](Set-ReconisCurrentRelease -Root $root -ReleaseId $ReleaseId)}
Write-Host "构建完成：$publishedJar"
Write-Host "ReleaseId：$ReleaseId；SHA256：$($manifest.jarSha256)"
if($NoSelect){Write-Host "尚未切换默认版本。验证后运行：scripts\select-release.ps1 -ReleaseId $ReleaseId"}
else{Write-Host 'DEMO 和 ALIPAY_SANDBOX 下次启动将使用此统一版本（显式程序包覆盖配置除外）；现有进程未被中断。'}
