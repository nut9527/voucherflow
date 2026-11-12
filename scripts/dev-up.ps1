# 启动本地数据库和缓存，并按参数启用供应商数据库与消息服务。
param([switch]$Messaging, [switch]$Supplier)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    docker compose --env-file .env up -d --wait --wait-timeout 120 mysql redis
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($Supplier) {
        docker compose --env-file .env --profile supplier up -d --wait --wait-timeout 120 supplier-db
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    # 供应商履约依赖可靠消息，因此 Supplier 模式同时启用 RocketMQ。
    if ($Messaging -or $Supplier) {
        docker compose --env-file .env --profile messaging up -d namesrv broker
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & (Join-Path $PSScriptRoot 'mq-bootstrap.ps1')
        exit $LASTEXITCODE
    }
} finally { Pop-Location }
