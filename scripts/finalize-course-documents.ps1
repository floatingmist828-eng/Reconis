#Requires -Version 5.1
param([string]$Directory="$PSScriptRoot\..\artifacts\delivery", [switch]$IncludePresentation)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$directoryPath=[IO.Path]::GetFullPath($Directory)
if(-not(Test-Path -LiteralPath $directoryPath -PathType Container)){throw 'Delivery directory does not exist.'}
$documents=@(Get-ChildItem -LiteralPath $directoryPath -Filter '*.docx' -File)
if($documents.Count -ne 2){throw 'Expected exactly two generated course reports.'}
$word=$null
try{
    $word=New-Object -ComObject Word.Application
    $word.Visible=$false
    $word.DisplayAlerts=0
    $word.AutomationSecurity=3
    foreach($file in $documents){
        $document=$null
        try{
            $document=$word.Documents.Open($file.FullName,$false,$false,$false)
            $document.Fields.Update() | Out-Null
            foreach($contents in $document.TablesOfContents){$contents.Update()}
            $document.Repaginate()
            $document.Save()
            $pdf=[IO.Path]::ChangeExtension($file.FullName,'.pdf')
            $document.ExportAsFixedFormat($pdf,17)
            Write-Host "Word PDF: $pdf"
        }finally{
            if($null -ne $document){$document.Close(0);[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)}
        }
    }
}finally{
    if($null -ne $word){
        if($word.Documents.Count -eq 0){$word.Quit()}
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
    }
}
if($IncludePresentation){
    $powerpoint=$null
    try{
        $powerpoint=New-Object -ComObject PowerPoint.Application
        $powerpoint.AutomationSecurity=3
        $decks=@(Get-ChildItem -LiteralPath $directoryPath -Filter '*.pptx' -File)
        foreach($file in $decks){
            $presentation=$null
            try{
                $presentation=$powerpoint.Presentations.Open($file.FullName,$true,$false,$false)
                $pdf=[IO.Path]::ChangeExtension($file.FullName,'.pdf')
                $presentation.SaveAs($pdf,32)
                Write-Host "PowerPoint PDF: $pdf"
            }finally{
                if($null -ne $presentation){$presentation.Close();[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($presentation)}
            }
        }
    }finally{
        if($null -ne $powerpoint){
            if($powerpoint.Presentations.Count -eq 0){$powerpoint.Quit()}
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($powerpoint)
        }
    }
}
[GC]::Collect()
[GC]::WaitForPendingFinalizers()
