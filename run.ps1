param([ValidateSet('run', 'test', 'createDistributable', 'packageMsi')][string]$Task = 'run')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += @(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
foreach ($base in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Amazon Corretto')) {
    $candidates += @(Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
}
$selectedJdk = $null
foreach ($candidate in $candidates) {
    $release = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
        if ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="(?:17|21)\.') { $selectedJdk = $candidate; break }
    }
}
if (!$selectedJdk) { throw 'Install JDK 17 or 21 and set JAVA_HOME. Compilation uses JDK 17.' }
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $selectedJdk
    & (Join-Path $PSScriptRoot 'gradlew.bat') $Task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { $env:JAVA_HOME = $previousJava }
