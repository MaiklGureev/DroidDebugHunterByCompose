param([Parameter(Mandatory = $true)][string]$ApplicationRoot)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ApplicationRoot).Path
$launcher = Join-Path $root 'DroidDebugHunterByCompose.exe'
if (!(Test-Path -LiteralPath $launcher)) { throw 'Native launcher was not built' }
$report = Join-Path ([IO.Path]::GetTempPath()) ('ddhc-runtime-' + [guid]::NewGuid().ToString('N') + '.json')
$previousPath = $env:PATH
$previousJava = $env:JAVA_HOME
$previousJre = $env:JRE_HOME
$previousJdk = $env:JDK_HOME
try {
    # Child processes cannot discover an installed JDK/JRE through PATH or Java environment variables.
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    $env:JAVA_HOME = Join-Path $root 'nonexistent-system-java'
    $env:JRE_HOME = $env:JAVA_HOME
    $env:JDK_HOME = $env:JAVA_HOME
    $process = Start-Process -FilePath $launcher -ArgumentList @('--runtime-check', ('"' + $report + '"')) -PassThru -WindowStyle Hidden
    if (!$process.WaitForExit(30000)) {
        & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID $process.Id /T /F | Out-Null
        throw 'Native runtime check timed out'
    }
    if ($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $report)) { throw 'Native launcher did not produce the runtime report' }
    $runtime = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $javaHome = [IO.Path]::GetFullPath($runtime.javaHome)
    $expected = [IO.Path]::GetFullPath((Join-Path $root 'runtime'))
    if (!$javaHome.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) { throw 'Launcher used a runtime outside the application folder' }
    if ($runtime.deviceSelection -ne $true -or $runtime.shutdown -ne $true) { throw 'Application selection/shutdown check did not pass' }
    if ($runtime.languagePersistence -ne $true) { throw 'Interface language persistence check did not pass' }
    Write-Output ('PASS: Java ' + $runtime.javaVersion + ' from bundled runtime; system Java paths disabled.')
    Write-Output 'PASS: packaged application selects a device and shuts down on the Swing dispatcher.'
    Write-Output 'PASS: English/Russian interface switching persists across application instances.'
} finally {
    $env:PATH = $previousPath
    $env:JAVA_HOME = $previousJava
    $env:JRE_HOME = $previousJre
    $env:JDK_HOME = $previousJdk
    if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report }
}
