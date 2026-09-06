@echo off
setlocal
echo ===================================================
echo   Building Smart Plugin Assistant EXE Package...
echo ===================================================

cd /d %~dp0

echo [1/3] Packaging Maven Fat JAR / Dependencies...
call .\mvnw.cmd clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Maven build failed!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/3] Preparing lib folder...
call .\mvnw.cmd dependency:copy-dependencies -DoutputDirectory=target/lib
copy target\SmartPluginAssistant-1.0.0-beta.jar target\lib\SmartPluginAssistant.jar

echo [3/3] Creating Windows EXE using jpackage...
if exist dist\SmartPluginAssistant rmdir /s /q dist\SmartPluginAssistant

jpackage ^
  --type app-image ^
  --name "SmartPluginAssistant" ^
  --input "target/lib" ^
  --main-jar "SmartPluginAssistant.jar" ^
  --main-class "com.sparxilium.smartpluginassistant.Launcher" ^
  --java-options "--enable-native-access=javafx.graphics,com.sun.jna" ^
  --dest "dist"

if %ERRORLEVEL% equ 0 (
    echo.
    echo ===================================================
    echo   BUILD SUCCESSFUL!
    echo   Executable located at: dist\SmartPluginAssistant\SmartPluginAssistant.exe
    echo ===================================================
) else (
    echo [ERROR] jpackage failed!
)

pause
