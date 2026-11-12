# 停止本地开发依赖容器，保留容器与数据卷供下次启动使用。
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose --env-file .env --profile messaging --profile supplier stop
    exit $LASTEXITCODE
} finally { Pop-Location }
