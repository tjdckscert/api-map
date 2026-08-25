@echo off
rem Auto-detect JAVA_HOME from Eclipse Adoptium install if not set
if "%JAVA_HOME%"=="" (
  for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%d"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call gradlew.bat bootRun
