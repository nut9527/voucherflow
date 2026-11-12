# 将备份导入新建的隔离数据库，并检查恢复后的 Flyway 迁移历史。
param([Parameter(Mandatory)][string]$Backup,
    [Parameter(Mandatory)][ValidatePattern('^vf_restore_[a-z0-9_]{1,32}$')][string]$Database,
    [ValidateSet('mysql','supplier-db')][string]$Service = 'mysql',
    [string[]]$ComposeFiles = @('compose.yaml'),
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')][string]$Project = 'voucherflow-local')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'compose-context.ps1')
$compose = Get-ProjectComposeArguments -ComposeFiles $ComposeFiles -Project $Project
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$backupPath = [IO.Path]::GetFullPath($Backup, $projectRoot)
if (!$backupPath.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup must be inside this project.'
}
if (!(Test-Path -LiteralPath $backupPath -PathType Leaf)) { throw 'Backup not found.' }
# 为本次恢复生成仅用于目标数据库的临时账户。
$temporary = @{
    VF_RESTORE_DATABASE = $Database
    VF_RESTORE_USER = 'vf_restore_' + [Guid]::NewGuid().ToString('N').Substring(0, 16)
    VF_RESTORE_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
}
$previous = @{}
foreach ($name in $temporary.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $temporary[$name], 'Process')
}
$environmentArguments = @('-e', 'VF_RESTORE_DATABASE', '-e', 'VF_RESTORE_USER', '-e', 'VF_RESTORE_PASSWORD')
$create = @'
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE $VF_RESTORE_DATABASE CHARACTER SET utf8mb4;
CREATE USER '$VF_RESTORE_USER'@'localhost' IDENTIFIED BY '$VF_RESTORE_PASSWORD';
GRANT ALL PRIVILEGES ON $VF_RESTORE_DATABASE.* TO '$VF_RESTORE_USER'@'localhost';
SQL
'@
$drop = @'
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
DROP USER IF EXISTS '$VF_RESTORE_USER'@'localhost';
SQL
'@
Push-Location $projectRoot
try {
    docker @compose exec -T @environmentArguments $Service sh -c $create
    if ($LASTEXITCODE -ne 0) { throw 'Destination must be a new isolated database.' }
    Get-Content -LiteralPath $backupPath -Encoding utf8 |
        docker @compose exec -T @environmentArguments $Service sh -c 'MYSQL_PWD="$VF_RESTORE_PASSWORD" exec mysql --binary-mode --local-infile=0 --default-character-set=utf8mb4 -u "$VF_RESTORE_USER" -D "$VF_RESTORE_DATABASE"'
    if ($LASTEXITCODE -ne 0) { throw 'Restore failed. The isolated database is retained for inspection.' }
    # 导入完成后确认迁移记录存在且无失败，恢复数据库保留供后续检查。
    $history = docker @compose exec -T @environmentArguments $Service sh -c 'MYSQL_PWD="$VF_RESTORE_PASSWORD" mysql -N -u "$VF_RESTORE_USER" -D "$VF_RESTORE_DATABASE" -e "SELECT COUNT(*),COALESCE(SUM(success=0),0) FROM flyway_schema_history"'
    if ($LASTEXITCODE -ne 0) { throw 'Migration history validation failed.' }
    $counts = ([string]$history).Split("`t")
    if ($counts.Count -ne 2 -or [long]$counts[0] -eq 0 -or [long]$counts[1] -ne 0) {
        throw 'Restored migration history is empty or contains a failed migration.'
    }
    Write-Host "Restored into isolated database $Database. Application configuration is unchanged."
} finally {
    # 清理临时数据库账户，并恢复当前进程原有的环境变量。
    docker @compose exec -T @environmentArguments $Service sh -c $drop
    $cleanupExitCode = $LASTEXITCODE
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
    if ($cleanupExitCode -ne 0) { throw 'Temporary restore account cleanup failed.' }
}
