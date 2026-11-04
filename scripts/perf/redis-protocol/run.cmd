@echo off
rem Redis candidate protocol harness - Windows CMD launcher.
rem Only calls the backend module test sources; no service, no HTTP, no full test run.
setlocal

set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%..\..\.."
set "BACKEND=%REPO_ROOT%\backend"
if "%MVN%"=="" set "MVN=mvn"

if not exist "%BACKEND%" (
  echo backend module not found: %BACKEND%
  exit /b 2
)

cd /d "%BACKEND%"

call "%MVN%" -o -q -DskipTests compile
if errorlevel 1 exit /b %errorlevel%
call "%MVN%" -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/redis-protocol-classpath.txt
if errorlevel 1 exit /b %errorlevel%

set /p DEPS=<target\redis-protocol-classpath.txt
set "CP=target\classes;%DEPS%"

if exist target\redis-protocol-test-classes rmdir /s /q target\redis-protocol-test-classes
mkdir target\redis-protocol-test-classes
if exist target\redis-protocol-sources.txt del target\redis-protocol-sources.txt
for /r "src\test\java\com\example\configmgr\perf\redis" %%f in (*.java) do (
  echo %%f | findstr /i /v "Test.java" >> target\redis-protocol-sources.txt
)
javac -encoding UTF-8 -proc:none -cp "%CP%" -d target\redis-protocol-test-classes @target\redis-protocol-sources.txt
if errorlevel 1 exit /b %errorlevel%

java -cp "target\redis-protocol-test-classes;%CP%" com.example.configmgr.perf.redis.HarnessMain %*
exit /b %errorlevel%
