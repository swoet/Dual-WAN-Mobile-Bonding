# Downloads Gradle 8.7 distribution and extracts gradle-wrapper.jar into app/android/gradle/wrapper
Param(
  [string]$GradleVersion = "8.7"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidDir = Join-Path $projectRoot "app\android"
$wrapperDir = Join-Path $androidDir "gradle\wrapper"
$wrapperJar = Join-Path $wrapperDir "gradle-wrapper.jar"

if (-not (Test-Path $wrapperDir)) {
  New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
}

$zipUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$zipPath = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
$extractDir = Join-Path $env:TEMP "gradle-$GradleVersion"

Write-Host "Downloading Gradle $GradleVersion ..."
Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath

if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }

Expand-Archive -Path $zipPath -DestinationPath $extractDir

# Find wrapper jar (path changes by version). Look for gradle-wrapper-*.jar
$jar = Get-ChildItem -Path $extractDir -Recurse -Filter "gradle-wrapper-*.jar" | Select-Object -First 1
if (-not $jar) {
  Write-Error "Could not find gradle-wrapper jar in extracted distribution"
}

Copy-Item -Force $jar.FullName $wrapperJar
Write-Host "Placed wrapper jar at $wrapperJar"
