# Installs evoker on Windows: downloads evoker.jar and an `evoker` launcher, and adds the folder to your user PATH.
#
#   irm https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.ps1 | iex
#
# Directory: $env:EVOKER_DIR, else %LOCALAPPDATA%\evoker
# Version:   $env:EVOKER_VERSION (e.g. v0.1.0), else the latest release
# Set $env:EVOKER_NO_MODIFY_PATH=1 to leave PATH alone.
$ErrorActionPreference = 'Stop'

$dir = if ($env:EVOKER_DIR) { $env:EVOKER_DIR } else { Join-Path $env:LOCALAPPDATA 'evoker' }
$version = if ($env:EVOKER_VERSION) { $env:EVOKER_VERSION } else { 'latest' }
$url = if ($version -eq 'latest') {
    'https://github.com/gavinhsmith/evoker/releases/latest/download/evoker.jar'
} else {
    "https://github.com/gavinhsmith/evoker/releases/download/$version/evoker.jar"
}
if ($env:EVOKER_URL) { $url = $env:EVOKER_URL } # override for testing: a URL or a local file

New-Item -ItemType Directory -Force -Path $dir | Out-Null
$dir = (Resolve-Path $dir).Path
$jar = Join-Path $dir 'evoker.jar'

Write-Host "Downloading evoker ($version) to $dir"
if (Test-Path -LiteralPath $url) {
    Copy-Item -LiteralPath $url -Destination "$jar.part" -Force
} else {
    Invoke-WebRequest -Uri $url -OutFile "$jar.part" -UseBasicParsing
}
Move-Item -Force "$jar.part" $jar

# evoker.cmd works from cmd.exe and PowerShell; uses %JAVA_HOME% if set, else java on PATH.
# (No parenthesized if-block: a JAVA_HOME like "C:\Program Files (x86)\..." would break it.)
Set-Content -Path (Join-Path $dir 'evoker.cmd') -Encoding ASCII -Value @(
    '@echo off',
    'setlocal',
    'set "EVOKER_JAVA=java"',
    'if defined JAVA_HOME set "EVOKER_JAVA=%JAVA_HOME%\bin\java"',
    '"%EVOKER_JAVA%" -jar "%~dp0evoker.jar" %*'
)

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
    Write-Warning 'Java not found. evoker needs Java 21 or newer (https://adoptium.net).'
} else {
    $info = (& $java -version 2>&1 | Out-String)
    if ($info -match 'version "(\d+)' -and [int]$Matches[1] -lt 21) {
        Write-Warning "Found Java $($Matches[1]); evoker needs Java 21 or newer (https://adoptium.net)."
    }
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($env:EVOKER_NO_MODIFY_PATH) { $userPath = $dir } # leave PATH alone (CI, testing)
if (($userPath -split ';') -notcontains $dir) {
    [Environment]::SetEnvironmentVariable('Path', ($(if ($userPath) { "$userPath;" } else { '' }) + $dir), 'User')
    Write-Host "Added $dir to your user PATH; open a new terminal to use 'evoker'."
}
Write-Host "Installed: $(& (Join-Path $dir 'evoker.cmd') version 2>$null)"
