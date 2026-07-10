# Git管理外ファイルを削除する

try
{

    # どこから実行しても、
    # このスクリプトがあるフォルダの、親のフォルダを、カレントディレクトリとする。
    $currentDir = Split-Path $MyInvocation.MyCommand.path;
    Set-Location $currentDir
    Set-Location ..


    
    # 対象を表示
    Write-Output "=== Delete Target ==="
    git clean -Xdn


    
    # 削除
    Write-Output "=== Delete ==="
    git clean -Xdfq

}
catch
{
    Write-Output "Throw And Stop"
}


# ルートに戻る
$currentDir = Split-Path $MyInvocation.MyCommand.path;
Set-Location $currentDir
Set-Location ..