@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================
rem Gitnuro one-click Windows build
rem
rem Usage:
rem   build.bat                 -> builds portable app image (Gitnuro.exe folder)
rem   build.bat portable        -> same as default
rem   build.bat installer       -> additionally builds Inno Setup installer (if ISCC.exe available)
rem   build.bat help            -> prints this message
rem
rem Outputs:
rem   dist\Gitnuro\Gitnuro.exe
rem   dist\installer\Gitnuro_Windows_Installer_<version>.exe  (installer mode)
rem ============================================================

pushd "%~dp0" >nul

if /i "%~1"=="help" goto :usage
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="/?" goto :usage

set "MODE=%~1"
if "%MODE%"=="" set "MODE=portable"

if /i not "%MODE%"=="portable" if /i not "%MODE%"=="installer" (
  echo ERROR: Unknown mode "%MODE%".
  echo.
  goto :usage
)

set "APP_VERSION="
echo Detecting app version...
call :detect_app_version
echo App version: %APP_VERSION%

call :ensure_rust_tools || goto :fail
call :ensure_java || goto :fail

echo.
echo === Building Gitnuro (%MODE%) ===
echo Version: %APP_VERSION%
echo.

call .\gradlew.bat --no-daemon createDistributable
if errorlevel 1 goto :fail

call :collect_artifacts || goto :fail

if /i "%MODE%"=="installer" (
  call :build_inno_installer || goto :fail
)

echo.
echo SUCCESS.
echo - App: "%CD%\dist\Gitnuro\Gitnuro.exe"
if /i "%MODE%"=="installer" (
  echo - Installer: "%CD%\dist\installer\"
)

popd >nul
exit /b 0

:usage
echo Gitnuro build script
echo.
echo Usage:
echo   build.bat ^<mode^>
echo.
echo Modes:
echo   portable   Builds the portable app image (default)
echo   installer  Builds portable app image + Inno Setup installer (requires Inno Setup 6)
echo.
echo Notes:
echo - Requires Rust (cargo) to be installed.
echo - If cargo-kotars is missing, it will be installed automatically.
echo - If Java is missing, a local Temurin JDK 17 will be downloaded into ".tooling\".
popd >nul
exit /b 0

:fail
echo.
echo BUILD FAILED. See errors above.
popd >nul
exit /b 1

:detect_app_version
if exist "latest.json" (
  rem Parse version from latest.json without PowerShell (keeps cmd parsing predictable)
  for /f "usebackq tokens=2 delims=:" %%A in (`findstr /i "\"appVersion\"" "latest.json"`) do (
    set "v=%%A"
    set "v=!v:,=!"
    set "v=!v: =!"
    set "v=!v:"=!"
    if not defined APP_VERSION set "APP_VERSION=!v!"
  )
)
if not defined APP_VERSION set "APP_VERSION=unknown"
exit /b 0

:ensure_rust_tools
set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"
echo Checking Rust/native prerequisites...

echo - locating cargo...
where cargo >nul 2>&1
set "CARGO_WHERE_RC=!errorlevel!"
echo - cargo where exitcode: !CARGO_WHERE_RC!
if not "!CARGO_WHERE_RC!"=="0" (
  echo ERROR: Rust toolchain not found ^(cargo^).
  echo Install Rust from https://www.rust-lang.org/tools/install and ensure "cargo" is in PATH.
  exit /b 1
)
echo - cargo OK

where cargo-kotars >nul 2>&1
if errorlevel 1 (
  echo cargo-kotars not found - installing...
  echo This can take a few minutes on the first run.
  cargo install cargo-kotars --git https://github.com/JetpackDuba/kotars
  if errorlevel 1 (
    echo ERROR: Failed to install cargo-kotars.
    exit /b 1
  )
)

where cargo-kotars >nul 2>&1
if errorlevel 1 (
  echo ERROR: cargo-kotars is still not available in PATH after install.
  echo Try reopening the terminal, or ensure "%USERPROFILE%\.cargo\bin" is in PATH.
  exit /b 1
)

call :ensure_perl || exit /b 1
call :ensure_nasm || exit /b 1
call :ensure_msvc || exit /b 1

exit /b 0

:ensure_perl
echo - Checking perl.exe...
if exist "%SystemDrive%\Strawberry\perl\bin\perl.exe" (
  set "PATH=%SystemDrive%\Strawberry\perl\bin;%PATH%"
  exit /b 0
)

where perl.exe >nul 2>&1
if not errorlevel 1 exit /b 0

rem Best-effort install via Chocolatey (if available)
where choco.exe >nul 2>&1
if not errorlevel 1 (
  echo perl.exe not found - installing Strawberry Perl via Chocolatey...
  choco install -y strawberryperl
  if exist "%SystemDrive%\Strawberry\perl\bin\perl.exe" (
    set "PATH=%SystemDrive%\Strawberry\perl\bin;%PATH%"
    exit /b 0
  )
)

echo ERROR: perl.exe not found.
echo - Install Strawberry Perl, or
echo - Ensure a full Perl distribution is installed and available in PATH.
exit /b 1

:ensure_nasm
echo - Checking nasm.exe...
where nasm.exe >nul 2>&1
if not errorlevel 1 exit /b 0

rem Try common install locations (Chocolatey NASM installer default)
if exist "%ProgramFiles%\NASM\nasm.exe" (
  set "PATH=%ProgramFiles%\NASM;%PATH%"
)
if exist "%ProgramFiles(x86)%\NASM\nasm.exe" (
  set "PATH=%ProgramFiles(x86)%\NASM;%PATH%"
)
where nasm.exe >nul 2>&1
if not errorlevel 1 exit /b 0

where choco.exe >nul 2>&1
if not errorlevel 1 (
  echo nasm.exe not found - installing NASM via Chocolatey...
  choco install -y nasm
)

where nasm.exe >nul 2>&1
if not errorlevel 1 exit /b 0

rem Chocolatey NASM installer may not add to PATH; try common install locations
if exist "%ProgramFiles%\NASM\nasm.exe" (
  set "PATH=%ProgramFiles%\NASM;%PATH%"
)
if exist "%ProgramFiles(x86)%\NASM\nasm.exe" (
  set "PATH=%ProgramFiles(x86)%\NASM;%PATH%"
)
where nasm.exe >nul 2>&1
if not errorlevel 1 exit /b 0

rem Fallback: download a portable NASM into .tooling\
call :bootstrap_nasm || exit /b 1
where nasm.exe >nul 2>&1
if not errorlevel 1 exit /b 0

echo ERROR: nasm.exe not found. Install NASM and ensure it is in PATH.
exit /b 1

:bootstrap_nasm
set "TOOLING_DIR=%CD%\.tooling"
set "NASM_ROOT=%TOOLING_DIR%\nasm"
set "NASM_ZIP=%TOOLING_DIR%\nasm-win64.zip"
set "NASM_URL=https://www.nasm.us/pub/nasm/releasebuilds/2.16.01/win64/nasm-2.16.01-win64.zip"

echo Downloading portable NASM into "%NASM_ROOT%" ...
if not exist "%TOOLING_DIR%" mkdir "%TOOLING_DIR%" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
  "Invoke-WebRequest -Uri '%NASM_URL%' -OutFile '%NASM_ZIP%'" 
if errorlevel 1 (
  echo ERROR: Failed to download NASM from "%NASM_URL%".
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "if (Test-Path '%NASM_ROOT%') { Remove-Item -Recurse -Force '%NASM_ROOT%' };" ^
  "New-Item -ItemType Directory -Path '%NASM_ROOT%' | Out-Null;" ^
  "Expand-Archive -Path '%NASM_ZIP%' -DestinationPath '%NASM_ROOT%' -Force"
if errorlevel 1 (
  echo ERROR: Failed to extract "%NASM_ZIP%".
  exit /b 1
)

set "NASM_BIN="
for /r "%NASM_ROOT%" %%F in (nasm.exe) do (
  if not defined NASM_BIN set "NASM_BIN=%%~dpF"
)
if not defined NASM_BIN (
  echo ERROR: NASM extraction succeeded but nasm.exe was not found under "%NASM_ROOT%".
  exit /b 1
)

set "PATH=%NASM_BIN%;%PATH%"
exit /b 0

:ensure_msvc
echo - Checking MSVC (cl.exe)...
where cl.exe >nul 2>&1
if not errorlevel 1 exit /b 0

echo MSVC tools not detected in PATH - trying to initialize Visual Studio environment...

call :try_vcvars "%ProgramFiles%\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles%\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles%\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
call :try_vcvars "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"

where cl.exe >nul 2>&1
if not errorlevel 1 exit /b 0

echo ERROR: MSVC toolchain not found (cl.exe).
echo Install Visual Studio / Build Tools with "Desktop development with C++" and retry.
exit /b 1

:try_vcvars
where cl.exe >nul 2>&1
if not errorlevel 1 exit /b 0
if exist "%~1" (
  echo - Using "%~1"
  call "%~1" >nul
)
exit /b 0

:ensure_java
rem 1) Use JAVA_HOME if valid
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ok

rem 2) Use java from PATH if available
set "JAVA_EXE="
for /f "delims=" %%J in ('where java 2^>nul') do (
  if not defined JAVA_EXE set "JAVA_EXE=%%J"
)
if defined JAVA_EXE (
  for %%I in ("%JAVA_EXE%") do set "JAVA_HOME=%%~dpI.."
  goto :java_ok
)

rem 3) Bootstrap a local JDK 17 (Temurin) into .tooling\
call :bootstrap_jdk17 || exit /b 1

:java_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: java.exe not found even after setup.
  exit /b 1
)

if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo ERROR: jpackage.exe not found in "%JAVA_HOME%\bin".
  echo Gitnuro packaging requires a full JDK ^(not just a JRE^).
  exit /b 1
)

exit /b 0

:bootstrap_jdk17
set "TOOLING_DIR=%CD%\.tooling"
set "JDK_ROOT=%TOOLING_DIR%\jdk17"
set "JDK_ZIP=%TOOLING_DIR%\temurin-jdk17.zip"
set "JDK_URL=https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"

rem Reuse already bootstrapped JDK if present
if exist "%JDK_ROOT%" (
  for /d %%D in ("%JDK_ROOT%\*") do (
    if exist "%%D\bin\java.exe" (
      set "JAVA_HOME=%%D"
      exit /b 0
    )
  )
)

echo Java not found - bootstrapping Temurin JDK 17 into "%JDK_ROOT%" ...

if not exist "%TOOLING_DIR%" mkdir "%TOOLING_DIR%" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
  "Invoke-WebRequest -Uri '%JDK_URL%' -OutFile '%JDK_ZIP%'" 
if errorlevel 1 (
  echo ERROR: Failed to download JDK from "%JDK_URL%".
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "if (Test-Path '%JDK_ROOT%') { Remove-Item -Recurse -Force '%JDK_ROOT%' };" ^
  "New-Item -ItemType Directory -Path '%JDK_ROOT%' | Out-Null;" ^
  "Expand-Archive -Path '%JDK_ZIP%' -DestinationPath '%JDK_ROOT%' -Force"
if errorlevel 1 (
  echo ERROR: Failed to extract "%JDK_ZIP%".
  exit /b 1
)

set "JAVA_HOME="
for /d %%D in ("%JDK_ROOT%\*") do (
  if exist "%%D\bin\java.exe" (
    set "JAVA_HOME=%%D"
    goto :jdk17_found
  )
)

echo ERROR: JDK extraction succeeded but JAVA_HOME could not be detected under "%JDK_ROOT%".
exit /b 1

:jdk17_found
exit /b 0

:collect_artifacts
set "APP_DIR=build\compose\binaries\main\app\Gitnuro"
if not exist "%APP_DIR%\Gitnuro.exe" (
  echo ERROR: Expected app image not found: "%CD%\%APP_DIR%\Gitnuro.exe"
  echo Gradle task "createDistributable" should create it.
  exit /b 1
)

if exist "dist" rmdir /s /q "dist"
mkdir "dist\Gitnuro" >nul 2>&1

robocopy "%APP_DIR%" "dist\Gitnuro" /E /NFL /NDL /NJH /NJS /NP >nul
set "RC=%ERRORLEVEL%"
if %RC% GEQ 8 (
  echo ERROR: Failed to copy app image to dist ^(robocopy exit code: %RC%^).
  exit /b %RC%
)

exit /b 0

:build_inno_installer
call :find_iscc || exit /b 1

if not exist "dist\installer" mkdir "dist\installer" >nul 2>&1

echo.
echo === Building installer (Inno Setup) ===
echo ISCC: "%ISCC%"

"%ISCC%" "gitnuro.iss" /DMyAppVersion=%APP_VERSION% /O"dist\installer"
if errorlevel 1 (
  echo ERROR: Inno Setup compilation failed.
  exit /b 1
)

exit /b 0

:find_iscc
set "ISCC="
for /f "delims=" %%I in ('where iscc 2^>nul') do (
  if not defined ISCC set "ISCC=%%I"
)

if not defined ISCC if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not defined ISCC if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"

if not defined ISCC (
  echo ERROR: Inno Setup compiler not found ^(ISCC.exe^).
  echo Install Inno Setup 6, or add ISCC.exe to PATH.
  exit /b 1
)

exit /b 0

