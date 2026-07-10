
rem "eclipse関連ファイルを生成する。(APT含む)"

cd "%~dp0"
cd ..

gradlew cleanEclipse eclipse eclipseJdtApt eclipseJdt eclipseFactorypath
