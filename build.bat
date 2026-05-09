@echo off
setlocal

set JAVA_HOME=E:\Program Files\Java\jdk-25
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is not set.
  echo set JAVA_HOME=E:\Program Files\Java\jdk-25
  exit /b 1
)
set PATH=%JAVA_HOME%\bin;%PATH%

set PACKAGE_TYPE=%~1
if "%PACKAGE_TYPE%"=="" set PACKAGE_TYPE=exe

if /I not "%PACKAGE_TYPE%"=="app-image" if /I not "%PACKAGE_TYPE%"=="exe" if /I not "%PACKAGE_TYPE%"=="msi" (
  echo Invalid package type: %PACKAGE_TYPE%
  echo Usage: build.bat [app-image^|exe^|msi]
  exit /b 1
)

set APP_NAME=FBSBarcode
set APP_VERSION=1.0.0
set MAIN_JAR=FBSBarcode-1.0-SNAPSHOT.jar
set MAIN_CLASS=com.tuandev.fbsbarcode.Launcher
set VENDOR=TuanDev
set INSTALLER_OPTIONS=
if /I "%PACKAGE_TYPE%"=="exe" set INSTALLER_OPTIONS=--win-menu --win-shortcut
if /I "%PACKAGE_TYPE%"=="msi" set INSTALLER_OPTIONS=--win-menu --win-shortcut

call mvnw.cmd -q package
if errorlevel 1 exit /b 1

if not exist "target\%MAIN_JAR%" (
  echo Missing main jar: target\%MAIN_JAR%
  exit /b 1
)

if exist out rmdir /s /q out

set JPACKAGE_COMMON=--name %APP_NAME% --input target --main-jar %MAIN_JAR% --main-class %MAIN_CLASS% --dest out --app-version %APP_VERSION% --vendor %VENDOR% --icon app.ico %INSTALLER_OPTIONS% --win-per-user-install --java-options "--enable-native-access=ALL-UNNAMED" --java-options "--enable-native-access=javafx.graphics" --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"

echo Building %PACKAGE_TYPE% package...
jpackage --type %PACKAGE_TYPE% %JPACKAGE_COMMON%
if errorlevel 1 exit /b 1

echo Done. Output is in the out folder.
endlocal
