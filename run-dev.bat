@echo off
REM Run the Spring Boot app using the Gradle wrapper.
REM Usage: run-dev.bat

SET ROOT_DIR=%~dp0
cd /d %ROOT_DIR%

IF EXIST gradlew (
  echo Starting app using Gradle wrapper...
  gradlew.bat bootRun
) ELSE (
  echo Gradle wrapper (gradlew.bat) not found in %ROOT_DIR%
  echo You can run: gradlew.bat bootRun
  exit /b 1
)
