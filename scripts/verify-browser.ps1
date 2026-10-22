#Requires -Version 7.4
param([string]$BaseUrl='http://127.0.0.1:8080',[string]$Filter)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$previous=$env:RECONIS_UI_URL
try {
 $env:RECONIS_UI_URL=$BaseUrl
 Push-Location (Join-Path $root 'frontend')
 try {
  $arguments=@('node_modules/@playwright/test/cli.js','test')
  if($Filter){$arguments+=@('--grep',$Filter)}
  & node @arguments
  if($LASTEXITCODE){throw '浏览器流程验证失败，详情见 artifacts/validation/browser-results.json。'}
 } finally {Pop-Location}
} finally {$env:RECONIS_UI_URL=$previous}
