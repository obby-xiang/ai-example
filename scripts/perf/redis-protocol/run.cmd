@echo off
rem Redis candidate protocol harness - Windows CMD launcher.
rem Only calls the backend module test sources; no service, no HTTP, no full test run.
rem PERF_MVN_MODE=online^|offline selects Maven dependency resolution (default online).
setlocal

set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%..\..\.."
set "BACKEND=%REPO_ROOT%\backend"
if "%MVN%"=="" set "MVN=mvn"

rem online (default): a fresh device may download missing dependencies on first run.
rem offline: pass -o and use the local repository only (keeps the repo's mvn -o habit).
if "%PERF_MVN_MODE%"=="" set "PERF_MVN_MODE=online"
set "MVN_OFFLINE_FLAG="
if /i "%PERF_MVN_MODE%"=="online" goto :mvn_mode_ok
if /i "%PERF_MVN_MODE%"=="offline" (
  set "MVN_OFFLINE_FLAG=-o"
  goto :mvn_mode_ok
)
echo PERF_MVN_MODE must be 'online' or 'offline' (got: %PERF_MVN_MODE%)
exit /b 2
:mvn_mode_ok

if not exist "%BACKEND%" (
  echo backend module not found: %BACKEND%
  exit /b 2
)

cd /d "%BACKEND%"
echo maven: %MVN% ^(PERF_MVN_MODE=%PERF_MVN_MODE%^) 1>&2

call "%MVN%" %MVN_OFFLINE_FLAG% -q -DskipTests compile
if errorlevel 1 (
  if /i "%PERF_MVN_MODE%"=="offline" echo offline build failed; rerun with PERF_MVN_MODE=online to resolve missing dependencies 1>&2
  exit /b 1
)
call "%MVN%" %MVN_OFFLINE_FLAG% -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/redis-protocol-classpath.txt
if errorlevel 1 exit /b %errorlevel%

set /p DEPS=<target\redis-protocol-classpath.txt
set "CP=target\classes;%DEPS%"

if exist target\redis-protocol-test-classes rmdir /s /q target\redis-protocol-test-classes
mkdir target\redis-protocol-test-classes
if exist target\redis-protocol-sources.txt del target\redis-protocol-sources.txt
rem Exclude only *Test.java by file name: matching the full path would also match
rem the "src\test\java" segment, because "." matches the backslash as a regex.
for /r "src\test\java\com\example\configmgr\perf\redis" %%f in (*.java) do (
  echo %%~nxf | findstr /i /v "Test.java" >nul && echo %%f>> target\redis-protocol-sources.txt
)
javac -encoding UTF-8 -proc:none -cp "%CP%" -d target\redis-protocol-test-classes @target\redis-protocol-sources.txt
if errorlevel 1 exit /b %errorlevel%

java -cp "target\redis-protocol-test-classes;%CP%" com.example.configmgr.perf.redis.HarnessMain %*
exit /b %errorlevel%
