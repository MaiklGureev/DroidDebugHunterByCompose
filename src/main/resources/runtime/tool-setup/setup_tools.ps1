param(
    [ValidateSet('init', 'check', 'download', 'manual')][string]$Action = 'init',
    [ValidateSet('all', 'frida', 'adb', 'python', 'server', 'gadget', 'capture', 'proxy', 'wireguard', 'templates', 'downloads')][string]$Component = 'all',
    [string]$ToolsRoot = '',
    [string]$Python = 'python',
    [string]$ArchivePath = '',
    [string]$Cache = '',
    [string]$FridaVersion = '',
    [string]$FridaToolsVersion = '',
    [string]$MitmproxyVersion = '',
    [string]$MitmproxyRsVersion = '',
    [string]$AdbVersion = '',
    [string]$WireGuardVersion = '',
    [switch]$Offline,
    [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
if (!$ToolsRoot) { $ToolsRoot = Join-Path $env:LOCALAPPDATA 'DroidDebugHunterCompose\tools\windows-x64' }
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$catalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tool-catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$policy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tool-versions.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$selected = @($catalog | Where-Object { $Component -eq 'all' -or $_.id -eq $Component -or ($Component -eq 'frida' -and $_.id -in @('capture', 'server', 'gadget')) })
if ($Action -in @('init', 'manual') -or $Component -in @('python', 'templates', 'downloads')) {
    foreach ($tool in $selected) {
        if ($tool.folder -notmatch '^[a-z][a-z0-9-]*$') { throw 'Invalid catalog folder.' }
        if ($Action -eq 'init') { [IO.Directory]::CreateDirectory((Join-Path $ToolsRoot $tool.folder)) | Out-Null }
        Write-Output ($tool.name + ': ' + $tool.instructions + "`n" + $tool.url)
        if ($tool.versionKey) { Write-Output ('Target: ' + $policy.($tool.versionKey)) }
        if ($Action -eq 'manual' -and !$NoOpen) { Start-Process -FilePath $tool.url -WindowStyle Hidden | Out-Null }
    }
    Write-Output 'Use the desktop setup wizard for portable Python bootstrap without a system interpreter.'
    return
}
$argsList = @((Join-Path $PSScriptRoot 'setup_tools.py'), '--root', $ToolsRoot, '--action', $Action, '--component', $Component)
if ($Offline) { $argsList += '--offline' }
if ($Cache) { $argsList += @('--cache', $Cache) }
$versions = @{ frida = $FridaVersion; fridaTools = $FridaToolsVersion; mitmproxy = $MitmproxyVersion; mitmproxyRs = $MitmproxyRsVersion; adb = $AdbVersion; wireguard = $WireGuardVersion }
foreach ($key in $versions.Keys) { if ($versions[$key]) { $argsList += @('--version-' + $key, $versions[$key]) } }
if ($ArchivePath) {
    if ($Component -ne 'adb') { throw 'Use -Cache with frida/ and wireguard/ subdirectories for local archives, or use the individual Python preparation helpers.' }
    $argsList += @('--adb-archive', $ArchivePath)
}
& $Python @argsList
if ($LASTEXITCODE -ne 0) { throw 'Tool preparation failed. Try a local venv, a writable user folder, another method in Settings or offline archives.' }
