param(
    [string]$Source = '',
    [string]$Python = 'python',
    [ValidateSet('init', 'check', 'download')][string]$Action = 'check',
    [ValidateSet('all', 'frida', 'adb', 'server', 'gadget', 'capture', 'proxy', 'wireguard')][string]$Component = 'all',
    [switch]$Offline,
    [string]$Cache = '',
    [string]$FridaVersion = '',
    [string]$FridaToolsVersion = '',
    [string]$MitmproxyVersion = '',
    [string]$MitmproxyRsVersion = '',
    [string]$AdbVersion = '',
    [string]$WireGuardVersion = '',
    [switch]$CopyPython
)
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot 'desktop-resources\windows-x64\tools'
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
    $prefix = & $Python -c 'import sys; assert sys.version_info >= (3,11) and sys.maxsize > 2**32; print(sys.prefix)'
    if ($LASTEXITCODE -ne 0) { throw 'Copying the interpreter requires 64-bit Python 3.11+. Target: 3.14.10; alternatives may need different wheels.' }
    $pythonTarget = Join-Path $target 'python'
    New-Item -ItemType Directory -Path $pythonTarget -Force | Out-Null
    foreach ($name in @('python.exe', 'pythonw.exe', 'python3.dll', 'python314.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'LICENSE.txt', 'DLLs', 'Lib')) {
        $path = Join-Path $prefix $name
        if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $pythonTarget -Recurse -Force }
    }
    Get-ChildItem -LiteralPath $prefix -Filter 'python*.dll' -File | Copy-Item -Destination $pythonTarget -Force
    $Python = Join-Path $pythonTarget 'python.exe'
}
if ($Python -in @('python', 'python.exe') -and (Test-Path -LiteralPath (Join-Path $target 'python\python.exe'))) { $Python = Join-Path $target 'python\python.exe' }
$argsList = @((Join-Path $PSScriptRoot 'src\main\resources\runtime\tool-setup\setup_tools.py'), '--root', $target, '--action', $Action, '--component', $Component)
if ($Offline) { $argsList += '--offline' }
if ($Cache) { $argsList += @('--cache', $Cache) }
$versions = @{ frida = $FridaVersion; fridaTools = $FridaToolsVersion; mitmproxy = $MitmproxyVersion; mitmproxyRs = $MitmproxyRsVersion; adb = $AdbVersion; wireguard = $WireGuardVersion }
foreach ($key in $versions.Keys) { if ($versions[$key]) { $argsList += @('--version-' + $key, $versions[$key]) } }
& $Python @argsList
if ($LASTEXITCODE -ne 0) { throw 'Tool preparation failed.' }
