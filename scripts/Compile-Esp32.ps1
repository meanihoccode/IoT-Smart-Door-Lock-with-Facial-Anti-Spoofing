param(
    [string]$Fqbn = 'esp32:esp32:esp32',
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
# Arduino requires the main sketch filename to match its directory name.
Copy-Item -LiteralPath (Join-Path $projectRoot 'esp32\main.ino') -Destination (Join-Path $sketchPath 'main.ino')
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'esp32') -Filter '*.h' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $sketchPath
}
& $CliPath compile @configArguments --fqbn $Fqbn --build-path $buildPath $sketchPath
if ($LASTEXITCODE -ne 0) { throw 'ESP32 compilation failed.' }
