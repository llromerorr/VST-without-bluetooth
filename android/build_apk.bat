@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo   WOB (Without Bluetooth) - Android APK Build Script
echo ========================================================

REM Configure paths according to your environment
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=C:\Android"
if "%BUILD_TOOLS%"=="" set "BUILD_TOOLS=%ANDROID_HOME%\build-tools\34.0.0"
if "%PLATFORM%"=="" set "PLATFORM=%ANDROID_HOME%\platforms\android-34\android.jar"
if "%DEBUG_KEYSTORE%"=="" set "DEBUG_KEYSTORE=%USERPROFILE%\.android\debug.keystore"

set "AAPT2=%BUILD_TOOLS%\aapt2.exe"
set "D8=%BUILD_TOOLS%\d8.bat"
set "ZIPALIGN=%BUILD_TOOLS%\zipalign.exe"
set "APKSIGNER=%BUILD_TOOLS%\apksigner.bat"

if not exist "%AAPT2%" (
    echo [ERROR] aapt2 not found at %AAPT2%
    echo Please set ANDROID_HOME environment variable properly.
    exit /b 1
)

set "APP_DIR=%~dp0"
set "BUILD_DIR=%APP_DIR%build"
set "GEN_DIR=%BUILD_DIR%\gen"
set "CLASSES_DIR=%BUILD_DIR%\classes"

if exist "%BUILD_DIR%" rd /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%" 2>nul
mkdir "%GEN_DIR%" 2>nul
mkdir "%CLASSES_DIR%" 2>nul
mkdir "%APP_DIR%bin" 2>nul

echo [1/6] Compiling resources...
"%AAPT2%" compile --dir "%APP_DIR%src\main\res" -o "%BUILD_DIR%\resources.zip"
if %ERRORLEVEL% NEQ 0 goto err

echo [2/6] Linking APK (minSdk 21, targetSdk 34)...
"%AAPT2%" link -I "%PLATFORM%" --manifest "%APP_DIR%src\main\AndroidManifest.xml" --min-sdk-version 21 --target-sdk-version 34 --version-code 2 --version-name "1.0.2" --java "%GEN_DIR%" -o "%BUILD_DIR%\base.apk" "%BUILD_DIR%\resources.zip" --auto-add-overlay
if %ERRORLEVEL% NEQ 0 goto err

echo [3/6] Compiling Java sources...
javac -cp "%PLATFORM%" -d "%CLASSES_DIR%" -source 8 -target 8 "%GEN_DIR%\com\antigravity\netaudio\R.java" "%APP_DIR%src\main\java\com\antigravity\netaudio\AudioCaptureService.java" "%APP_DIR%src\main\java\com\antigravity\netaudio\MainActivity.java"
if %ERRORLEVEL% NEQ 0 goto err

echo [4/6] Converting to DEX (minApi 21)...
pushd "%CLASSES_DIR%"
call "%D8%" --min-api 21 --lib "%PLATFORM%" --output "%BUILD_DIR%" com\antigravity\netaudio\*.class
popd
if %ERRORLEVEL% NEQ 0 goto err

echo [5/6] Adding DEX to APK...
pushd "%BUILD_DIR%"
jar -uf "base.apk" "classes.dex"
popd

echo [6/6] Zipaligning and Signing (v1, v2, v3)...
"%ZIPALIGN%" -f -p 4 "%BUILD_DIR%\base.apk" "%BUILD_DIR%\aligned.apk"
call "%APKSIGNER%" sign --ks "%DEBUG_KEYSTORE%" --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "%APP_DIR%bin\WOB.apk" "%BUILD_DIR%\aligned.apk"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo   [SUCCESS] APK created: %APP_DIR%bin\WOB.apk
    echo ========================================================
    exit /b 0
)

:err
echo [ERROR] Build failed.
exit /b 1
