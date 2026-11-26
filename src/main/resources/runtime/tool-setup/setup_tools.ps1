param(
    [ValidateSet('init', 'check', 'download', 'manual')][string]$Action = 'init',
    [ValidateSet('all', 'adb', 'python', 'server', 'gadget', 'capture', 'proxy', 'wireguard', 'templates', 'downloads')][string]$Component = 'all',
    [string]$ToolsRoot = '',
    [string]$Python = 'python',
    [string]$ArchivePath = '',
    [switch]$Offline,
    [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$env:PYTHONIOENCODING = 'utf-8'
if (!$ToolsRoot) {
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '..\DroidDebugHunter\DroidDebugHunter.csproj')) {
        $ToolsRoot = Join-Path $PSScriptRoot '..\DroidDebugHunter'
    } else { $ToolsRoot = Join-Path $PSScriptRoot '..' }
}
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$catalogFile = Join-Path $PSScriptRoot 'tool-catalog.json'
if (!(Test-Path -LiteralPath $catalogFile)) { $catalogFile = Join-Path $PSScriptRoot '..\DroidDebugHunter\Tooling\tool-catalog.json' }
$catalog = Get-Content -LiteralPath $catalogFile -Raw -Encoding UTF8 | ConvertFrom-Json

function Tool-Folder($tool) {
    if ($tool.folder -notmatch '^[a-z][a-z0-9-]*$') { throw 'Invalid tool folder in catalog.' }
    $path = [IO.Path]::GetFullPath((Join-Path $ToolsRoot $tool.folder))
    $prefix = $ToolsRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (!$path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Tool folder escaped the destination.' }
    return $path
}
foreach ($tool in $catalog) {
    $folder = Tool-Folder $tool
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $note = Join-Path $folder 'PLACEMENT.txt'
    if (!(Test-Path -LiteralPath $note)) {
        [IO.File]::WriteAllText($note, ($tool.name + "`n`n" + $tool.instructions + "`n`n" + $tool.url + "`n"), (New-Object Text.UTF8Encoding($false)))
    }
}
Write-Output ('Tools root: ' + $ToolsRoot)
$selected = @($catalog | Where-Object { $Component -eq 'all' -or $_.id -eq $Component })
function Open-Manual($tool) {
    Write-Output ($tool.name + ': ' + $tool.url)
    Write-Output ('Place files in: ' + (Tool-Folder $tool))
    Write-Output $tool.instructions
    if (!$NoOpen) { Start-Process -FilePath $tool.url | Out-Null }
}
if ($Action -eq 'manual') {
    foreach ($tool in $selected) { Open-Manual $tool }
    return
}
if ($Action -eq 'init' -or $Action -eq 'check') {
    foreach ($tool in $selected) {
        $folder = Tool-Folder $tool
        if ($tool.id -eq 'python') {
            $localPython = Join-Path $ToolsRoot 'python\python.exe'
            $command = if ($Python -in @('python', 'python.exe') -and (Test-Path -LiteralPath $localPython)) { $localPython } else { $Python }
            $resolved = Get-Command $command -ErrorAction SilentlyContinue
            $state = if ($resolved) { 'Interpreter found: ' + $resolved.Source + ' (verify 3.14 x64 before capture)' } else { 'Interpreter not found; install Python 3.14 x64 or configure its full path.' }
            Write-Output ($tool.name + ' | ' + $folder + ' | ' + $state)
            continue
        }
        $missing = @($tool.files | Where-Object { !(Test-Path -LiteralPath (Join-Path $folder $_) -PathType Leaf) })
        $state = if ($missing.Count) { 'Missing: ' + ($missing -join ', ') } else { 'Files present / folder created' }
        Write-Output ($tool.name + ' | ' + $folder + ' | ' + $state)
    }
    return
}
if ($Component -eq 'python') { Open-Manual ($catalog | Where-Object id -eq 'python'); return }
if ($Component -in @('templates', 'downloads')) { Write-Output 'This folder is prepared locally. See PLACEMENT.txt.'; return }

# Prefer an explicitly selected interpreter; a local installation precedes PATH for the default command.
if ($Python -in @('python', 'python.exe') -and (Test-Path -LiteralPath (Join-Path $ToolsRoot 'python\python.exe'))) {
    $Python = Join-Path $ToolsRoot 'python\python.exe'
}
try {
    & $Python -c 'import sys; assert sys.version_info[:2] == (3,14) and sys.maxsize > 2**32, "Use Python 3.14 x64"'
    if ($LASTEXITCODE -ne 0) { throw 'Use Python 3.14 x64.' }
} catch {
    Write-Output 'Automatic preparation requires Python 3.14 x64. Nothing is installed globally.'
    Open-Manual ($catalog | Where-Object id -eq 'python')
    throw
}
function Run-Python([string[]]$Arguments) {
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Preparation failed: ' + ($Arguments -join ' ')) }
}
foreach ($tool in $selected) {
    $folder = Tool-Folder $tool
    Write-Output ('Preparing: ' + $tool.name)
    switch ($tool.id) {
        'adb' {
            $argsList = @((Join-Path $PSScriptRoot 'prepare_platform_tools.py'), '--output', $folder, '--cache', (Join-Path $ToolsRoot 'downloads\adb\platform-tools-windows.zip'))
            if ($ArchivePath) { $argsList += @('--archive', $ArchivePath) }
            if ($Offline) { $argsList += '--offline' }
            Run-Python $argsList
        }
        'server' {
            $archives = if ($ArchivePath) { $ArchivePath } else { Join-Path $ToolsRoot 'downloads\frida' }
            $argsList = @((Join-Path $PSScriptRoot 'prepare_frida.py'), '--output', $folder, '--archives', $archives)
            if ($Offline) { $argsList += '--unpack-only' }
            Run-Python $argsList
        }
        'gadget' {
            $archives = if ($ArchivePath) { $ArchivePath } else { Join-Path $ToolsRoot 'downloads\frida' }
            $argsList = @((Join-Path $PSScriptRoot 'prepare_extra_tools.py'), '--root', $ToolsRoot, '--component', 'gadget', '--archives', $archives)
            if ($Offline) { $argsList += '--offline' }
            Run-Python $argsList
        }
        'wireguard' {
            $argsList = @((Join-Path $PSScriptRoot 'prepare_extra_tools.py'), '--root', $ToolsRoot, '--component', 'wireguard')
            if ($ArchivePath) { $argsList += @('--apk', $ArchivePath) }
            if ($Offline) { $argsList += '--offline' }
            Run-Python $argsList
        }
        'capture' {
            $options = @{ Python = $Python; TargetPath = $folder }
            if ($Offline) { $options.Offline = $true; $options.Wheelhouse = Join-Path $ToolsRoot 'downloads\wheelhouse\frida' }
            & (Join-Path $PSScriptRoot 'prepare_capture.ps1') @options
        }
        'proxy' {
            $options = @{ Python = $Python; TargetPath = $folder }
            if ($Offline) { $options.Offline = $true; $options.Wheelhouse = Join-Path $ToolsRoot 'downloads\wheelhouse\proxy' }
            & (Join-Path $PSScriptRoot 'prepare_proxy.ps1') @options
        }
    }
}
Write-Output 'Preparation completed. Check capture modes in the app before starting a device session.'
