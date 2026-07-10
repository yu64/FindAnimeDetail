
# Gradle関連の生成物を削除する

try
{

    # どこから実行しても、
    # このスクリプトがあるフォルダの、親のフォルダを、カレントディレクトリとする。
    $currentDir = Split-Path $MyInvocation.MyCommand.path;
    Set-Location $currentDir
    Set-Location ..

    # 削除対象の名前
    $targetName = @(
        "build",
        "bin",
        ".project"
        ".classpath"
        ".settings",
        ".factorypath"
        )

    # 配下から対象を取得
    $targetItems =  Get-ChildItem -Recurse | Where-Object {$targetName.Contains($_.Name)} | ForEach-Object {$_.FullName}

    # 対象を表示
    Write-Output "=== Delete Target ==="
    $targetItems | Write-Output

    # 削除
    Write-Output "=== Delete ==="
    Remove-Item -Path $targetItems -Force

}
catch
{
    Write-Output "Throw And Stop"
}
