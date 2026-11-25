@echo off
echo ========================================================
echo   WOB 6.0 (Without Bluetooth) - VST2 64-bit Build Script
echo ========================================================

where gcc >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] GCC compiler not found in PATH!
    echo Please install MinGW-w64 or MSYS2.
    exit /b 1
)

if not exist bin mkdir bin

echo Compiling src/WOB.c into bin/WOB-6.0.dll ...
gcc -shared -O3 -Wall -Wextra ^
    -o "bin\WOB-6.0.dll" ^
    "src\WOB.c" ^
    -lws2_32 -lgdi32 -luser32 -s

if %ERRORLEVEL% EQU 0 (
    copy /y "bin\WOB-6.0.dll" "bin\WOB.dll" >nul
    copy /y "bin\WOB-6.0.dll" "C:\Program Files\VSTPlugins\WOB.dll" >nul 2>nul
    copy /y "bin\WOB-6.0.dll" "C:\Program Files\REAPER (x64)\Plugins\FX\WOB.dll" >nul 2>nul
    echo [SUCCESS] Build completed: bin\WOB-6.0.dll
    echo Installed to C:\Program Files\VSTPlugins\ and REAPER FX folder!
) else (
    echo [ERROR] Compilation failed.
    exit /b 1
)
