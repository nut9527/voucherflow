# 等待 RocketMQ 注册完成，再创建业务主题和消费者组。
param([string[]]$ComposeFiles = @('compose.yaml'),
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')][string]$Project = 'voucherflow-local')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'compose-context.ps1')
$compose = Get-ProjectComposeArguments -ComposeFiles $ComposeFiles -Project $Project
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $cluster = docker @compose exec -T broker sh mqadmin clusterList -n namesrv:9876 2>&1
        if ($LASTEXITCODE -eq 0 -and ($cluster -join "`n") -match 'voucherflow-broker') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'RocketMQ broker registration did not become ready.' }
    foreach ($topic in @('vf_fulfillment', 'vf_notification')) {
        docker @compose exec -T broker sh mqadmin updateTopic -n namesrv:9876 -c VoucherFlowCluster -t $topic -a '+message.type=NORMAL'
        if ($LASTEXITCODE -ne 0) { throw "Cannot provision topic $topic" }
    }
    # 更新消费者组配置时保留既有消费位点，供服务重启后继续消费。
    foreach ($group in @('vf_fulfillment_worker', 'vf_notification_worker')) {
        docker @compose exec -T broker sh mqadmin updateSubGroup -n namesrv:9876 -c VoucherFlowCluster -g $group -m true -r 5
        if ($LASTEXITCODE -ne 0) { throw "Cannot provision consumer group $group" }
    }
    Write-Host 'RocketMQ topics and consumer groups are ready. Existing consumer offsets are preserved.'
} finally { Pop-Location }
