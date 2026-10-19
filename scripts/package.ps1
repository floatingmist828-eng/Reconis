param([string]$Output="$PSScriptRoot\..\artifacts\Reconis-source.zip")
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$target=[IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
Push-Location $root
try{git archive --format=zip --output=$target HEAD;if($LASTEXITCODE){throw '源码打包失败'};Get-FileHash -LiteralPath $target -Algorithm SHA256}finally{Pop-Location}
