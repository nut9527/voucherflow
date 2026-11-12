# 将指定数据库导出到项目内备份目录，并输出备份文件的 SHA-256。
param([ValidateSet('mysql','supplier-db')][string]$Service = 'mysql',
    [string[]]$ComposeFiles = @('compose.yaml'),
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')][string]$Project = 'voucherflow-local')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'compose-context.ps1')
$compose = Get-ProjectComposeArguments -ComposeFiles $ComposeFiles -Project $Project
$projectRoot = Split-Path -Parent $PSScriptRoot
$backupDirectory = Join-Path $projectRoot '.local/backups'
New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
$outputFile = Join-Path $backupDirectory ($Service + '-' + [Guid]::NewGuid().ToString('N') + '.sql')
$partialFile = $outputFile + '.partial'
Push-Location $projectRoot
try {
    docker @compose exec -T $Service sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --hex-blob --routines --triggers --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$MYSQL_DATABASE"' |
        Set-Content -LiteralPath $partialFile -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'Database backup failed; no completed backup was written.' }
    # 导出成功后才将临时文件改为正式备份，避免把部分数据误当成完整快照。
    Move-Item -LiteralPath $partialFile -Destination $outputFile
    Write-Host "Backup saved: $outputFile"
    Get-FileHash -LiteralPath $outputFile -Algorithm SHA256 | Select-Object Hash, Path
} finally {
    if (Test-Path -LiteralPath $partialFile) { Remove-Item -LiteralPath $partialFile }
    Pop-Location
}
