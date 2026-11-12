# 初始化项目本地连接配置及随机凭证，已有配置仅补充缺失项。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'

function New-LocalPassword {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
}

$lines = @(
    'VF_DB_HOST=127.0.0.1', 'VF_DB_PORT=3308', 'VF_DB_NAME=voucherflow', 'VF_DB_USER=vf_app'
    "VF_DB_PASSWORD=$(New-LocalPassword)"
    "VF_MYSQL_ROOT_PASSWORD=$(New-LocalPassword)"
    "VF_DEMO_ADMIN_PASSWORD=$(New-LocalPassword)"
    "VF_DEMO_USER_PASSWORD=$(New-LocalPassword)"
    'VF_REDIS_PORT=6380'
    'VF_SUPPLIER_DB_PORT=3309'
    "VF_SUPPLIER_DB_PASSWORD=$(New-LocalPassword)"
    "VF_SUPPLIER_MYSQL_ROOT_PASSWORD=$(New-LocalPassword)"
    "VF_SUPPLIER_API_TOKEN=$(New-LocalPassword)"
    "VF_SUPPLIER_CALLBACK_SECRET=$(New-LocalPassword)"
)
if (Test-Path -LiteralPath $envPath) {
    $existing = [IO.File]::ReadAllLines($envPath)
    $keys = @($existing | Where-Object { $_ -match '^VF_[A-Z0-9_]+=' } | ForEach-Object { ($_ -split '=',2)[0] })
    $missing = @($lines | Where-Object { ($_ -split '=',2)[0] -notin $keys })
    [IO.File]::WriteAllLines($envPath, @($existing) + $missing, [Text.UTF8Encoding]::new($false))
    Write-Host 'Added missing local settings; existing credentials are preserved.'
} else {
    [IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host 'Created .env with random database and account passwords.'
}
Write-Host 'Accounts: admin-demo / user-demo. Read passwords from your local .env.'
