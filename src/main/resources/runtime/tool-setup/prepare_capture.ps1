param([switch]$Offline, [string]$Python = 'python', [string]$Wheelhouse = "$PSScriptRoot\wheelhouse", [string]$TargetPath = "")
$ErrorActionPreference = 'Stop'
if (!$TargetPath) { $TargetPath = Join-Path $PSScriptRoot '..\DroidDebugHunter\frida-python' }
$captureTarget = [IO.Path]::GetFullPath($TargetPath)
# This prepares a local client directory; global Python packages are not modified.
if ($Offline) {
    & $Python -m pip install --no-index --find-links $Wheelhouse --target $captureTarget -r "$PSScriptRoot\capture-requirements.txt"
} else {
    & $Python -m pip install --target $captureTarget -r "$PSScriptRoot\capture-requirements.txt"
}
if ($LASTEXITCODE -ne 0) { throw 'Frida client preparation failed. Check pip output; the client is not ready.' }
Write-Host "Prepared local Frida client: $captureTarget"
Write-Host 'Rebuild DroidDebugHunter before running capture. Python is required on the target computer.'
