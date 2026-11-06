[CmdletBinding()]
param(
  [string]$DocxPath = (Join-Path $PSScriptRoot '..\final\Reconis-系统实现总结报告.docx')
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $DocxPath).Path
$expectedName = 'Reconis-系统实现总结报告.docx'
if ([IO.Path]::GetFileName($source) -ne $expectedName) {
  throw "Only $expectedName may be finalized by this script."
}
$pdfPath = [IO.Path]::ChangeExtension($source, '.pdf')
$parentFolder = Split-Path -Parent $source
$protectedFiles = @('Reconis-系统设计报告.docx', 'Reconis-系统设计报告.pdf')
$protectedHashes = @{}
foreach ($name in $protectedFiles) {
  $candidate = Join-Path $parentFolder $name
  if (Test-Path -LiteralPath $candidate) {
    $protectedHashes[$candidate] = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
  }
}

$word = $null
$document = $null
$ownsApplication = $false
$started = Get-Date
try {
  $word = New-Object -ComObject Word.Application
  # Never quit a shared Word instance containing somebody else's documents.
  if ($word.Documents.Count -ne 0) {
    throw 'Word returned an instance with existing documents; no document was changed.'
  }
  $ownsApplication = $true
  $word.Visible = $false
  $word.DisplayAlerts = 0
  $word.AutomationSecurity = 3
  $document = $word.Documents.Open($source, $false, $false, $false)
  if ($document.ReadOnly) {
    throw 'The implementation report is open read-only; close the other editor before retrying.'
  }
  if ($document.TablesOfContents.Count -lt 1) {
    throw 'The document has no native TOC field; build the implementation report first.'
  }

  # Author metadata is already written by python-docx. Avoid the Office
  # property collection's incompatible dynamic COM binder in PowerShell 7.
  [void]$document.Fields.Update()
  for ($i = 1; $i -le $document.Sections.Count; $i++) {
    $section = $document.Sections.Item($i)
    foreach ($collection in @($section.Headers, $section.Footers)) {
      for ($j = 1; $j -le $collection.Count; $j++) {
        $story = $collection.Item($j)
        if ($story.Exists) { [void]$story.Range.Fields.Update() }
      }
    }
  }
  # The populated TOC can change pagination; update page numbers after repagination.
  for ($pass = 0; $pass -lt 2; $pass++) {
    for ($i = 1; $i -le $document.TablesOfContents.Count; $i++) {
      $document.TablesOfContents.Item($i).Update()
    }
    $document.Repaginate()
  }
  for ($i = 1; $i -le $document.TablesOfContents.Count; $i++) {
    $document.TablesOfContents.Item($i).UpdatePageNumbers()
  }
  $document.Repaginate()
  $pages = $document.ComputeStatistics(2)
  $tableCount = $document.Tables.Count
  $imageCount = $document.InlineShapes.Count
  $tocText = $document.TablesOfContents.Item(1).Range.Text
  if ([string]::IsNullOrWhiteSpace($tocText) -or $tocText -match 'No table of contents entries found|未找到目录项') {
    throw 'Word could not populate the TOC; the document must be corrected before export.'
  }
  $document.Save()
  # PDF, print quality, entire document, heading bookmarks and tagged structure.
  $document.ExportAsFixedFormat($pdfPath, 17, $false, 0, 0, 1, 1, 0, $true, $true, 1, $true, $true, $false)
  if (-not (Test-Path -LiteralPath $pdfPath) -or (Get-Item -LiteralPath $pdfPath).Length -eq 0) {
    throw 'Word returned without a nonempty PDF.'
  }
  $document.Close(0)
  [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)
  $document = $null
  foreach ($entry in $protectedHashes.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash -ne $entry.Value) {
      throw "Protected initial design file changed during finalization: $($entry.Key)"
    }
  }
  [pscustomobject]@{
    Docx = $source
    Pdf = $pdfPath
    Pages = $pages
    Tables = $tableCount
    Images = $imageCount
    TocUpdated = $true
    InitialDesignUnchanged = $true
    Seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 3)
  } | Format-List
}
finally {
  if ($null -ne $document) {
    try { $document.Close(0) } catch { Write-Warning $_.Exception.Message }
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)
  }
  if ($null -ne $word) {
    if ($ownsApplication) {
      try { $word.Quit(0) } catch { Write-Warning $_.Exception.Message }
    }
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
  }
  [GC]::Collect()
  [GC]::WaitForPendingFinalizers()
}
