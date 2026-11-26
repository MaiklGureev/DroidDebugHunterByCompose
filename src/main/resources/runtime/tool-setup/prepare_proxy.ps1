param([switch]$Offline, [string]$Python = 'python', [string]$Wheelhouse = "$PSScriptRoot\proxy-wheelhouse", [string]$TargetPath = "")
$ErrorActionPreference = 'Stop'
if (!$TargetPath) { $TargetPath = Join-Path $PSScriptRoot '..\DroidDebugHunter\proxy-python' }
$proxyTarget = [IO.Path]::GetFullPath($TargetPath)
& $Python -c 'import sys; assert sys.version_info >= (3, 12) and sys.maxsize > 2**32, "Proxy requires Python 3.12+ x64 (prepared bundle: 3.14 x64)"'
if ($LASTEXITCODE -ne 0) { throw 'Incompatible Python interpreter.' }
$pipArgs = @('-m', 'pip', 'install', '--upgrade', '--only-binary=:all:', '--target', $proxyTarget, '-r', "$PSScriptRoot\proxy-requirements.txt")
if ($Offline) { $pipArgs += @('--no-index', '--find-links', $Wheelhouse) }
& $Python @pipArgs
if ($LASTEXITCODE -ne 0) { throw 'Proxy dependency preparation failed. Check pip output.' }
$priorPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = $proxyTarget
    & $Python -c 'from mitmproxy.version import VERSION; import mitmproxy_rs, h11, hpack; print("Proxy backend:", VERSION)'
    if ($LASTEXITCODE -ne 0) { throw 'Prepared proxy dependencies cannot be imported.' }
} finally { $env:PYTHONPATH = $priorPythonPath }
Write-Host 'Local proxy/VPN dependencies prepared. Rebuild DroidDebugHunter.'
