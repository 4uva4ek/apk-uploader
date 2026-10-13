param(
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$Stage,
    [Parameter(Mandatory=$true)][int]$ParentProcessId,
    [Parameter(Mandatory=$true)][string]$Log
)
$ErrorActionPreference = 'Stop'
$oldMoved = $false
$newMoved = $false
try {
    $targetPath = (Resolve-Path -LiteralPath $Target).Path
    $stagePath = (Resolve-Path -LiteralPath $Stage).Path
    $parentPath = Split-Path -LiteralPath $targetPath
    if ((Split-Path -LiteralPath $stagePath) -ne $parentPath -or
        !(Split-Path -Leaf $stagePath).StartsWith('.ApkUploader-update-')) { throw 'Invalid staging directory' }
    $newPath = Join-Path $stagePath 'ApkUploader'
    $backupPath = Join-Path $parentPath ('.ApkUploader-backup-' + [guid]::NewGuid().ToString('N'))
    foreach ($appPath in @($targetPath, $newPath)) {
        foreach ($relative in @('ApkUploader.exe', 'app\ApkUploader.cfg', 'runtime\bin\server\jvm.dll')) {
            if (!(Test-Path -LiteralPath (Join-Path $appPath $relative) -PathType Leaf)) { throw 'Incomplete installation' }
        }
    }
    Set-Content -LiteralPath (Join-Path $stagePath 'ready') -Value 'Ready'
    $deadline = (Get-Date).AddSeconds(120)
    while (Get-Process -Id $ParentProcessId -ErrorAction SilentlyContinue) {
        if ((Get-Date) -gt $deadline) { throw 'APK Uploader did not close; installation cancelled' }
        Start-Sleep -Milliseconds 200
    }
    # Resolved siblings only. No recursive deletion; the previous application is retained.
    Move-Item -LiteralPath $targetPath -Destination $backupPath
    $oldMoved = $true
    Move-Item -LiteralPath $newPath -Destination $targetPath
    $newMoved = $true
    Start-Process -FilePath (Join-Path $targetPath 'ApkUploader.exe') -WorkingDirectory $targetPath -WindowStyle Hidden
    Set-Content -LiteralPath $Log -Value "Update installed. Previous version: $backupPath"
} catch {
    $reason = $_.Exception.Message
    try {
        if ($newMoved) { Move-Item -LiteralPath $targetPath -Destination (Join-Path $stagePath 'failed-app') }
        if ($oldMoved) {
            Move-Item -LiteralPath $backupPath -Destination $targetPath
            Start-Process -FilePath (Join-Path $targetPath 'ApkUploader.exe') -WorkingDirectory $targetPath -WindowStyle Hidden
        }
        Set-Content -LiteralPath $Log -Value "Update failed: $reason. Previous installation preserved."
    } catch {
        Set-Content -LiteralPath $Log -Value "Update failed: $reason. Recovery failed: $_. Previous version: $backupPath"
    }
    exit 1
}
