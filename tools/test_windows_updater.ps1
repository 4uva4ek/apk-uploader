$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$root = Join-Path $repo ('app\build\updater-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$fixture = Join-Path $root 'fixture.exe'
Add-Type -TypeDefinition 'public class UpdaterFixture { public static void Main() {} }' -OutputAssembly $fixture -OutputType WindowsApplication
$helper = Join-Path $repo 'app\src\main\resources\update-windows.ps1'
foreach ($case in @('success', 'rollback', 'incomplete')) {
    $caseDir = Join-Path $root $case
    $target = Join-Path $caseDir 'App with space and ''quote'
    $stage = Join-Path $caseDir '.ApkUploader-update-test'
    $new = Join-Path $stage 'ApkUploader'
    foreach ($dir in @($target, $new)) {
        New-Item -ItemType Directory -Path (Join-Path $dir 'runtime\bin\server') -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $dir 'app') -Force | Out-Null
        Copy-Item -LiteralPath $fixture -Destination (Join-Path $dir 'ApkUploader.exe')
        Set-Content -LiteralPath (Join-Path $dir 'runtime\bin\server\jvm.dll') -Value 'fixture'
        Set-Content -LiteralPath (Join-Path $dir 'app\ApkUploader.cfg') -Value 'fixture'
    }
    Set-Content -LiteralPath (Join-Path $target 'old-marker') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $new 'new-marker') -Value 'new'
    if ($case -eq 'rollback') { Set-Content -LiteralPath (Join-Path $new 'ApkUploader.exe') -Value 'invalid executable' }
    if ($case -eq 'incomplete') { Rename-Item -LiteralPath (Join-Path $new 'ApkUploader.exe') -NewName 'missing.exe' }
    $log = Join-Path $caseDir 'result.txt'
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $helper -Target $target -Stage $stage -ParentProcessId 2147483647 -Log $log
    $result = Get-Content -LiteralPath $log -Raw
    if ($case -eq 'success') {
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $target 'new-marker'))) { throw "Install failed: $result" }
        $backup = Get-ChildItem -LiteralPath $caseDir -Directory -Filter '.ApkUploader-backup-*'
        if (!$backup -or !(Test-Path -LiteralPath (Join-Path $backup.FullName 'old-marker'))) { throw 'Backup missing' }
    } else {
        if ($LASTEXITCODE -ne 1 -or !(Test-Path -LiteralPath (Join-Path $target 'old-marker')) -or $result.Contains('Recovery failed')) { throw "Preservation failed: $result" }
    }
    Write-Output "$case PASS"
}
Write-Output "Fixtures retained: $root"
