@echo off
echo ========================================================
echo   WOB 3.0 (Without Bluetooth) - VST2 64-bit Build Script
echo ========================================================

where gcc >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] GCC compiler not found in PATH!
    echo Please install MinGW-w64 or MSYS2.
    exit /b 1
)

if not exist bin mkdir bin

echo Compiling src/WOB.c into bin/WOB-3.0.dll ...
gcc -shared -O3 -Wall -Wextra ^
    -o "bin\WOB-3.0.dll" ^
    "src\WOB.c" ^
    -lws2_32 -lgdi32 -luser32 -s

if %ERRORLEVEL% EQU 0 (
    copy /y "bin\WOB-3.0.dll" "bin\WOB.dll" >nul
    echo [SUCCESS] Build completed: bin\WOB-3.0.dll
    echo Copy bin\WOB-3.0.dll to your VST plugin folder (e.g. C:\Program Files\VSTPlugins\^)
) else (
    echo [ERROR] Compilation failed.
    exit /b 1
)
