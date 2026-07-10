
# Javaプロセスを全て停止する。

# どこから実行しても、
# このスクリプトがあるフォルダの、親のフォルダを、カレントディレクトリとする。
$currentDir = Split-Path $MyInvocation.MyCommand.path;
Set-Location $currentDir
Set-Location ..

Get-Process | Where-Object {$_.ProcessName -eq "java"} | Stop-Process


