@echo off
REM Run the Spring Boot app using the Gradle wrapper with the dev profile.
REM Usage: run-dev.bat

SET ROOT_DIR=%~dp0
cd /d %ROOT_DIR%

IF EXIST gradlew (
  echo Starting app with dev profile using Gradle wrapper...
  gradlew.bat bootRun
) ELSE (
  echo Gradle wrapper (gradlew.bat) not found in %ROOT_DIR%
  echo You can run: gradlew.bat bootRun --args="--spring.profiles.active=dev"
  exit /b 1
)
