@echo off
rem JAVA_HOME이 없으면 Eclipse Adoptium 설치 경로에서 자동 감지
if "%JAVA_HOME%"=="" (
  for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%d"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call gradlew.bat bootRun
