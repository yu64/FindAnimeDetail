
rem "eclipse関連ファイルを生成する。"

cd %~dp0/..
gradlew cleanEclipse eclipse
pause
