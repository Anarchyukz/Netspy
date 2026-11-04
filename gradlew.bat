@echo off
setlocal
set GRADLE_VERSION=8.13
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set GRADLE_BIN=%GRADLE_USER_HOME%\netspy-wrapper\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%GRADLE_USER_HOME%\netspy-wrapper" mkdir "%GRADLE_USER_HOME%\netspy-wrapper"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest $u -OutFile '%GRADLE_USER_HOME%\netspy-wrapper\gradle.zip'; Expand-Archive -Force '%GRADLE_USER_HOME%\netspy-wrapper\gradle.zip' '%GRADLE_USER_HOME%\netspy-wrapper'"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" %*
endlocal
