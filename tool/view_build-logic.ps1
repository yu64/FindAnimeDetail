
# どこから実行しても、
# このスクリプトがあるフォルダの、親のフォルダを、カレントディレクトリとする。
$currentDir = Split-Path $MyInvocation.MyCommand.path;
Set-Location $currentDir
Set-Location ..


Get-ChildItem "./build-logic/*/src/*" -Recurse  | 
    Where-Object {$_.Name -match '\.gradle(.kts)?$'} |
    ForEach-Object { 
        
        $obj = New-Object PSObject | Select-Object Name, Comment
        $obj.Name = $_.Name -replace "\.gradle(.kts)?$", ""
        $obj.Comment = Get-Content $_ -Encoding UTF8 | Select-String -Pattern "^//[^= ].+$"
        $obj

    } | Write-Output