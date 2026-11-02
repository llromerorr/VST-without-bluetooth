@echo off
echo ========================================================
echo   WOB (Without Bluetooth) - VST2 64-bit Build Script
echo ========================================================

where gcc >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] GCC compiler not found in PATH!
    echo Please install MinGW-w64 or MSYS2.
    exit /b 1
)

if not exist bin mkdir bin

echo Compiling src/WOB.c into bin/WOB.dll ...
gcc -shared -O3 -Wall -Wextra ^
    -o "bin\WOB.dll" ^
    "src\WOB.c" ^
    -lws2_32 -lgdi32 -luser32 -s

if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] Build completed: bin\WOB.dll
    echo Copy bin\WOB.dll to your VST plugin folder (e.g. C:\Program Files\VSTPlugins\^)
) else (
    echo [ERROR] Compilation failed.
    exit /b 1
)
