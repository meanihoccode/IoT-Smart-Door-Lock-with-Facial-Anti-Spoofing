# Compile only; never uploads. The board is ESP32-S3 N16R8 (16 MB flash, 8 MB octal PSRAM), so there is no
# default FQBN: verify options with `arduino-cli board details -b esp32:esp32:esp32s3` and pass them explicitly.
param(
    [Parameter(Mandatory = $true)][string]$Fqbn,
    [string]$CliPath = $env:ARDUINO_CLI,
    [string]$ConfigPath = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $CliPath) {
    $cliCommand = Get-Command arduino-cli -ErrorAction SilentlyContinue
    if ($cliCommand) { $CliPath = $cliCommand.Source }
    elseif ($env:ProgramFiles) { $CliPath = Join-Path $env:ProgramFiles 'Arduino CLI\arduino-cli.exe' }
}
if (-not $CliPath -or -not (Test-Path -LiteralPath $CliPath)) {
    throw 'Install Arduino CLI on PATH, or pass -CliPath with its executable path.'
}
$configArguments = @()
if ($ConfigPath) {
    if (-not (Test-Path -LiteralPath $ConfigPath)) { throw 'The specified Arduino configuration does not exist.' }
    $configArguments = @('--config-file', $ConfigPath)
} else {
    $localConfig = Join-Path $projectRoot '.runtime\arduino-cli.yaml'
    if (Test-Path -LiteralPath $localConfig) { $configArguments = @('--config-file', $localConfig) }
}
# A fresh staging directory avoids accidentally reusing an old private config.h.
$sketchPath = Join-Path $projectRoot ('.runtime\sketch\' + [guid]::NewGuid().ToString('N') + '\main')
$buildPath = Join-Path $projectRoot '.runtime\esp32-build'
New-Item -ItemType Directory -Force $sketchPath | Out-Null
# Canonical firmware source is the Arduino IDE sketch folder esp32/main (main.ino + bundled PubSubClient).
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'esp32\main') -File |
    Where-Object { $_.Extension -in '.ino', '.cpp', '.h' } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $sketchPath }
# Private config.h (if any) wins over the placeholder example.
foreach ($name in 'config.h', 'config.example.h') {
    $candidate = Join-Path $projectRoot ('esp32\' + $name)
    if ((Test-Path -LiteralPath $candidate) -and -not (Test-Path -LiteralPath (Join-Path $sketchPath $name))) {
        Copy-Item -LiteralPath $candidate -Destination $sketchPath
    }
}
& $CliPath compile @configArguments --fqbn $Fqbn --build-path $buildPath $sketchPath
if ($LASTEXITCODE -ne 0) { throw 'ESP32 compilation failed.' }
