param(
    [string]$Source = '',
    [string]$Python = 'python',
    [ValidateSet('init', 'check', 'download')][string]$Action = 'check',
    [ValidateSet('all', 'adb', 'server', 'gadget', 'capture', 'proxy', 'wireguard')][string]$Component = 'all',
    [switch]$CopyPython
)
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot 'desktop-resources\common\tools'
New-Item -ItemType Directory -Path $target -Force | Out-Null
if ($Source) {
    $sourceRoot = (Resolve-Path -LiteralPath $Source).Path
    if (Test-Path -LiteralPath (Join-Path $sourceRoot 'DroidDebugHunter\platform-tools')) { $sourceRoot = Join-Path $sourceRoot 'DroidDebugHunter' }
    foreach ($name in @('platform-tools', 'frida-server', 'frida-gadget', 'frida-python', 'proxy-python', 'android-tools', 'android-debug')) {
        $folder = Join-Path $sourceRoot $name
        if (Test-Path -LiteralPath $folder -PathType Container) { Copy-Item -LiteralPath $folder -Destination $target -Recurse -Force }
    }
    Write-Output 'Copied tooling. No capture files, certificates or settings were copied.'
}
if ($CopyPython) {
    $prefix = & $Python -c 'import sys; assert sys.version_info[:2] == (3,14) and sys.maxsize > 2**32; print(sys.prefix)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.14 x64 is required.' }
    $pythonTarget = Join-Path $target 'python'
    New-Item -ItemType Directory -Path $pythonTarget -Force | Out-Null
    foreach ($name in @('python.exe', 'pythonw.exe', 'python3.dll', 'python314.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'LICENSE.txt', 'DLLs', 'Lib')) {
        $path = Join-Path $prefix $name
        if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $pythonTarget -Recurse -Force }
    }
    $Python = Join-Path $pythonTarget 'python.exe'
}
& (Join-Path $PSScriptRoot 'src\main\resources\runtime\tool-setup\setup_tools.ps1') -Action $Action -Component $Component -ToolsRoot $target -Python $Python -NoOpen
