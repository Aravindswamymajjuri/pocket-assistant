@echo off
setlocal
set "TARGET=%LOCALAPPDATA%\Programs\PocketAssistant"
set "DATA=%LOCALAPPDATA%\PocketAssistant"
taskkill /IM PocketAssistant.exe /F >nul 2>&1
if exist "%TARGET%" rmdir /S /Q "%TARGET%"
del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Pocket Assistant.lnk" >nul 2>&1
choice /M "Also delete your assistant data, including Voice ID and the audit log"
if %ERRORLEVEL% EQU 1 if exist "%DATA%" rmdir /S /Q "%DATA%"
echo Pocket Assistant was removed. Your Gemma model file was not touched.
pause
