# 暂停两端写入后保存成对数据库快照、待恢复事件及对应运行制品信息。
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{0,30}$')][string]$Run,
    [switch]$PreparePending
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'recovery-runtime.ps1')
. (Join-Path $PSScriptRoot 'api-session.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$files = @('compose.yaml', 'deploy/compose.container.yaml')
$compose = Get-ProjectComposeArguments -ComposeFiles $files
$destination = Join-Path $root ".local/recovery/$Run/snapshot"
if (Test-Path -LiteralPath $destination) { throw 'Snapshot already exists; choose a new run.' }
$running = @(docker @compose ps --status running --services)
if ($LASTEXITCODE -ne 0 -or 'app' -notin $running -or 'supplier' -notin $running) {
    throw 'The source app and supplier services must both be running.'
}
$orders = @(Get-RecoveryOrders $compose voucherflow)
$artifacts = Get-RecoveryArtifacts $compose
if (!($orders | Where-Object status -eq 'FULFILLED') -or !($orders | Where-Object status -eq 'FAILED')) {
    throw 'Snapshot verification requires existing supplier success and rejection records.'
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$pendingOrder = $null
try {
    # 可选地暂停供应商并创建待履约订单，作为恢复后续跑的验收样本。
    if ($PreparePending) {
        docker @compose stop -t 30 supplier
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the source supplier.' }
        $settings = Get-LocalSettings
        $admin = New-VfApiSession -BaseUrl 'http://127.0.0.1:8080' -Username 'admin-demo' -Password $settings['VF_DEMO_ADMIN_PASSWORD']
        try {
            $now = [datetime]::UtcNow
            $campaign = Invoke-VfApi $admin '/api/admin/campaigns' POST -ExpectedStatus 201 -Body @{
                name = "recovery-$Run"; description = '恢复验收'; totalStock = 2
                startAt = $now.AddMinutes(-1).ToString('o'); endAt = $now.AddHours(2).ToString('o')
                redeemEndAt = $now.AddHours(3).ToString('o'); fulfillmentMode = 'SUPPLIER'
                supplierId = 'simulator'; productCode = 'recovery'; contractVersion = 'v1'
            }
            $null = Invoke-VfApi $admin ('/api/admin/campaigns/' + $campaign.id + '/publish') POST
        } finally { Close-VfApiSession $admin }
        $user = New-VfApiSession -BaseUrl 'http://127.0.0.1:8080' -Username 'user-demo' -Password $settings['VF_DEMO_USER_PASSWORD']
        try { $pendingOrder = Invoke-VfApi $user ('/api/campaigns/' + $campaign.id + '/claim') POST -ExpectedStatus 202 }
        finally { Close-VfApiSession $user }
        $durable = $false
        $orderId = [long]$pendingOrder.orderId
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            $state = Invoke-RecoverySql $compose mysql voucherflow "SELECT job_state FROM fulfillment_orders WHERE id=$orderId"
            if ($state -in @('READY', 'RUNNING', 'PAUSED')) { $durable = $true; break }
            Start-Sleep -Seconds 1
        }
        if (!$durable) { throw 'The new order did not reach durable worker registration.' }
    }
    # 先停止两端写入，再读取清单与导出数据，使两个数据库处于同一业务边界。
    docker @compose stop -t 45 app supplier
    if ($LASTEXITCODE -ne 0) { throw 'Source writers did not stop cleanly.' }
    $active = @(docker @compose ps --status running --services)
    if ('app' -in $active -or 'supplier' -in $active) { throw 'Source writer is still running.' }
    $main = Get-RecoveryDatabaseManifest $compose mysql voucherflow
    $supplier = Get-RecoveryDatabaseManifest $compose supplier-db vf_supplier
    $orders = @(Get-RecoveryOrders $compose voucherflow)
    # 单独归档数据库中已发送但未确认消费的事件，弥补恢复时消息代理状态的缺口。
    $missingSql = @'
SELECT JSON_OBJECT('eventId',o.id,'eventType',o.event_type,'body',CAST(o.payload AS CHAR))
FROM outbox_events o LEFT JOIN consumed_events c ON c.event_id=o.id
 AND c.consumer_name=CASE WHEN o.event_type='FulfillmentRequested.v1' THEN 'vf_fulfillment_worker' ELSE 'vf_notification_worker' END
WHERE o.state='SENT' AND c.event_id IS NULL ORDER BY o.id LIMIT 1001
'@
    $missing = @(Invoke-RecoverySql $compose mysql voucherflow $missingSql | ForEach-Object { $_ | ConvertFrom-Json })
    if ($missing.Count -gt 1000) { throw 'Too many unconsumed events for one recovery archive.' }
    $archives = @()
    foreach ($item in $missing) {
        $event = $item.body | ConvertFrom-Json
        if ($item.eventType -notin @('FulfillmentRequested.v1', 'VoucherIssued.v1', 'FulfillmentFailed.v1') -or
                $event.eventId -ne $item.eventId -or $event.eventType -ne $item.eventType -or [string]::IsNullOrWhiteSpace($item.body)) {
            throw 'Recovery event identity or payload is invalid.'
        }
        $occurred = [DateTimeOffset]$event.occurredAt
        if ($occurred -lt [DateTimeOffset]::UtcNow.AddDays(-7) -or $occurred -gt [DateTimeOffset]::UtcNow.AddSeconds(60)) {
            throw 'An unconsumed event is outside the permitted replay window.'
        }
        $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($item.body))).ToLowerInvariant()
        $archives += [ordered]@{
            consumerGroup = $(if ($item.eventType -eq 'FulfillmentRequested.v1') { 'vf_fulfillment_worker' } else { 'vf_notification_worker' })
            messageId = 'snapshot-outbox'; body = $item.body; bodySha256 = $hash; archivedAt = $occurred.ToString('o')
        } | ConvertTo-Json -Compress
    }
    [IO.File]::WriteAllLines((Join-Path $destination 'sent-unconsumed.jsonl'), [string[]]$archives, [Text.UTF8Encoding]::new($false))
    $dumps = @{}
    foreach ($service in @('mysql', 'supplier-db')) {
        $backup = & (Join-Path $PSScriptRoot 'backup-local.ps1') -Service $service -ComposeFiles $files
        $path = Join-Path $destination "$service.sql"
        Move-Item -LiteralPath $backup.Path -Destination $path
        $dumps[$service] = [ordered]@{ file = "$service.sql"; sha256 = $backup.Hash }
    }
    [ordered]@{
        run = $Run; sourceProject = 'voucherflow-local'; writersStopped = @('app', 'supplier')
        main = $main; supplier = $supplier; orders = $orders; pendingOrderId = $pendingOrder.orderId
        dumps = $dumps; unconsumedSentEvents = $missing.Count
        artifacts = $artifacts
        archive = [ordered]@{ file = 'sent-unconsumed.jsonl'; count = $missing.Count
            sha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'sent-unconsumed.jsonl') -Algorithm SHA256).Hash }
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8NoBOM
    Write-Host "Paired snapshot saved: $destination"
} finally {
    # 快照结束后恢复原环境服务运行。
    docker @compose start supplier app
    if ($LASTEXITCODE -ne 0) { throw 'Snapshot ended, but source writer restart failed.' }
    Wait-RecoveryHealth 'http://127.0.0.1:8090/actuator/health'
    Wait-RecoveryHealth 'http://127.0.0.1:8080/actuator/health/readiness'
}
