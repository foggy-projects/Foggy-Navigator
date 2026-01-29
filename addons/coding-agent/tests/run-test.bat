@echo off
REM Quick test launcher for Coding Agent
REM Usage: run-test.bat

echo ========================================
echo Coding Agent E2E Test Launcher
echo ========================================
echo.

REM Check if backend is running
echo Checking backend service...
curl -s -f http://localhost:8112/actuator/health >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Backend service is not running on port 8112
    echo.
    echo Please start the backend first:
    echo   cd D:\foggy-projects\Foggy-Navigator
    echo   powershell -ExecutionPolicy Bypass -File start-launcher.ps1
    echo.
    pause
    exit /b 1
)

echo [OK] Backend service is running
echo.

REM Check if node_modules exists
if not exist "node_modules" (
    echo Installing dependencies...
    call npm install
    echo.
)

REM Run the test
echo Starting E2E test...
echo Browser will open automatically
echo.
echo Manual test steps will be displayed in the console
echo.

call npm run test:create-file

echo.
echo Test completed!
echo Screenshots saved to: tests\e2e\screenshots\
echo.
pause
