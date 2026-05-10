@echo off
setlocal enabledelayedexpansion

:: Auto-detect JAVA_HOME if not set
if "%JAVA_HOME%"=="" (
    for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do set JAVA_HOME=%%i
    :: Remove leading spaces from java.home value
    for /f "tokens=*" %%i in ("!JAVA_HOME!") do set JAVA_HOME=%%i
)
if "%JAVA_HOME%"=="" (
    echo JAVA_HOME could not be detected. Please install JDK 25 and set JAVA_HOME.
    exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

set PACKAGE_TYPE=%~1
if "%PACKAGE_TYPE%"=="" set PACKAGE_TYPE=exe

if /I not "%PACKAGE_TYPE%"=="app-image" if /I not "%PACKAGE_TYPE%"=="exe" if /I not "%PACKAGE_TYPE%"=="msi" (
    echo Invalid package type: %PACKAGE_TYPE%
    echo Usage: build.bat [app-image^|exe^|msi]
    exit /b 1
)

set APP_NAME=FBSBarcode
:: Read app.version from pom.xml using Maven evaluate (reliable)
for /f "delims=" %%a in ('mvnw.cmd help:evaluate -Dexpression^=app.version -q -DforceStdout 2^>nul') do set APP_VERSION=%%a
if "%APP_VERSION%"=="" (
    echo Could not read app.version from pom.xml, using default
    set APP_VERSION=1.0.0
)
echo Building version: %APP_VERSION%

set MAIN_JAR=FBSBarcode-%APP_VERSION%.jar
set MAIN_CLASS=com.tuandev.fbsbarcode.Launcher
set VENDOR=TuanDev
set INSTALLER_OPTIONS=
if /I "%PACKAGE_TYPE%"=="exe" set INSTALLER_OPTIONS=--win-menu --win-shortcut --win-per-user-install
if /I "%PACKAGE_TYPE%"=="msi" set INSTALLER_OPTIONS=--win-menu --win-shortcut --win-per-user-install

echo Packaging with Maven...
call mvnw.cmd -q package
if errorlevel 1 exit /b 1

if not exist "target\%MAIN_JAR%" (
    echo Missing main jar: target\%MAIN_JAR%
    exit /b 1
)

if exist out rmdir /s /q out

set JPACKAGE_COMMON=--name %APP_NAME% --input target --main-jar %MAIN_JAR% --main-class %MAIN_CLASS% --dest out --app-version %APP_VERSION% --vendor %VENDOR% --icon app.ico %INSTALLER_OPTIONS% --java-options "--enable-native-access=ALL-UNNAMED" --java-options "--enable-native-access=javafx.graphics" --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"

echo Building %PACKAGE_TYPE% package...
jpackage --type %PACKAGE_TYPE% %JPACKAGE_COMMON%
if errorlevel 1 exit /b 1

echo Done. Output is in the out folder.
endlocal
