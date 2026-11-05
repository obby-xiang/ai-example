@echo off
rem Direct production-class Redis harness; no HTTP service and no project tests.
setlocal
set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%..\..\.."
set "BACKEND=%REPO_ROOT%\backend"
if "%MVN%"=="" set "MVN=mvn"
if "%PERF_MVN_MODE%"=="" set "PERF_MVN_MODE=online"
set "MVN_OFFLINE_FLAG="
if /i "%PERF_MVN_MODE%"=="online" goto :mvn_mode_ok
if /i "%PERF_MVN_MODE%"=="offline" (
  set "MVN_OFFLINE_FLAG=-o"
  goto :mvn_mode_ok
)
echo PERF_MVN_MODE must be online or offline
exit /b 2
:mvn_mode_ok
cd /d "%BACKEND%"
call "%MVN%" %MVN_OFFLINE_FLAG% -q -DskipTests compile
if errorlevel 1 exit /b 1
call "%MVN%" %MVN_OFFLINE_FLAG% -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/redis-production-classpath.txt
if errorlevel 1 exit /b 1
set /p DEPS=<target\redis-production-classpath.txt
set "CP=target\classes;%DEPS%"
if exist target\redis-production-test-classes rmdir /s /q target\redis-production-test-classes
mkdir target\redis-production-test-classes
if exist target\redis-production-sources.txt del target\redis-production-sources.txt
for /r "src\test\java\com\example\configmgr\perf\redis" %%f in (*.java) do (
  echo %%~nxf | findstr /i /v "Test.java" >nul && echo %%f>> target\redis-production-sources.txt
)
for /r "src\test\java\com\example\configmgr\perf\production" %%f in (*.java) do (
  echo %%~nxf | findstr /i /v "Test.java" >nul && echo %%f>> target\redis-production-sources.txt
)
javac -encoding UTF-8 -proc:none -cp "%CP%" -d target\redis-production-test-classes @target\redis-production-sources.txt
if errorlevel 1 exit /b 1
java -cp "target\redis-production-test-classes;%CP%" com.example.configmgr.perf.production.ProductionHarnessMain %*
exit /b %errorlevel%
