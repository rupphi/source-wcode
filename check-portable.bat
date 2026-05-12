@echo off
setlocal
chcp 65001 >nul

set "APP_DIR=%~dp0"
set "LOG_FILE=%APP_DIR%portable-diagnostic.txt"

(
echo ===== WCode Portable Diagnostic =====
echo Date: %date% %time%
echo APP_DIR: %APP_DIR%
echo.

echo [1] Windows version
ver
echo.

echo [2] Current user and profile
echo USERNAME=%USERNAME%
echo USERPROFILE=%USERPROFILE%
echo LOCALAPPDATA=%LOCALAPPDATA%
echo APPDATA=%APPDATA%
echo.

echo [3] App folder contents
dir "%APP_DIR%"
echo.

echo [4] Check required portable structure
if exist "%APP_DIR%WCode.exe" (
  echo OK: WCode.exe found
) else (
  echo ERROR: WCode.exe missing
)

if exist "%APP_DIR%app" (
  echo OK: app folder found
) else (
  echo ERROR: app folder missing
)

if exist "%APP_DIR%runtime" (
  echo OK: runtime folder found
) else (
  echo ERROR: runtime folder missing
)

if exist "%APP_DIR%runtime\bin\java.dll" (
  echo OK: runtime\bin\java.dll found
) else (
  echo ERROR: runtime\bin\java.dll missing
)
echo.

echo [5] Runtime bin contents
if exist "%APP_DIR%runtime\bin" (
  dir "%APP_DIR%runtime\bin"
) else (
  echo runtime\bin folder not found
)
echo.

echo [6] Try launching app and capture console output
pushd "%APP_DIR%"
"%APP_DIR%WCode.exe" 1>"%APP_DIR%portable-stdout.txt" 2>"%APP_DIR%portable-stderr.txt"
set "EXIT_CODE=%ERRORLEVEL%"
popd
echo Exit code: %EXIT_CODE%
echo.

echo [7] portable-stdout.txt
if exist "%APP_DIR%portable-stdout.txt" (
  type "%APP_DIR%portable-stdout.txt"
) else (
  echo portable-stdout.txt not created
)
echo.

echo [8] portable-stderr.txt
if exist "%APP_DIR%portable-stderr.txt" (
  type "%APP_DIR%portable-stderr.txt"
) else (
  echo portable-stderr.txt not created
)
echo.

echo [9] App logs
if exist "%LOCALAPPDATA%\WCode\logs\startup.log" (
  echo startup.log found:
  type "%LOCALAPPDATA%\WCode\logs\startup.log"
) else (
  echo startup.log not found at %LOCALAPPDATA%\WCode\logs\startup.log
)
echo.

echo [10] Event Viewer hints
echo Please check Windows Logs ^> Application for:
echo - Application Error
echo - Windows Error Reporting
echo - .NET Runtime
echo - Entries mentioning WCode, java, javaw, sqlite
echo.
) > "%LOG_FILE%"

type "%LOG_FILE%"
echo.
echo Saved diagnostic report to:
echo %LOG_FILE%
echo.
echo Please send these files back:
echo - portable-diagnostic.txt
echo - portable-stdout.txt
echo - portable-stderr.txt
echo - %%LOCALAPPDATA%%\WCode\logs\startup.log ^(if it exists^)

endlocal
