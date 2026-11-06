@echo off
setlocal
set "TARGET=%LOCALAPPDATA%\Programs\PocketAssistant"
echo Installing Pocket Assistant to %TARGET%
taskkill /IM PocketAssistant.exe /F >nul 2>&1
if exist "%TARGET%" rmdir /S /Q "%TARGET%"
robocopy "%~dp0PocketAssistant" "%TARGET%" /E /NFL /NDL /NJH /NJS /NP >nul
if %ERRORLEVEL% GEQ 8 (
  echo Copy failed.
  pause
  exit /b 1
)
powershell.exe -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs')+'\Pocket Assistant.lnk');$s.TargetPath=$env:TARGET+'\PocketAssistant.exe';$s.Save()"
echo Done. Open Pocket Assistant from the Start menu.
pause
