@ECHO OFF
SETLOCAL
SET APP_BASE_DIR=%~dp0
SET WRAPPER_JAR=%APP_BASE_DIR%\gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO Gradle wrapper JAR not found at %WRAPPER_JAR%
  ECHO Please run: powershell -ExecutionPolicy Bypass -File ..\..\scripts\setup_gradle_wrapper.ps1
  EXIT /B 1
)

IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)

"%JAVA_EXE%" -jar "%WRAPPER_JAR%" %*
