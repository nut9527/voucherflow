# 分阶段准备、启动并验证主应用与供应商的隔离恢复环境。
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{0,30}$')][string]$Run,
    [Parameter(Mandatory)][ValidateSet('Prepare', 'Start', 'Verify')][string]$Action
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'recovery-runtime.ps1')
. (Join-Path $PSScriptRoot 'api-session.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$directory = Join-Path $root ".local/recovery/$Run"
$snapshot = Join-Path $directory 'snapshot'
$manifest = Get-Content -LiteralPath (Join-Path $snapshot 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.run -ne $Run) { throw 'Recovery manifest does not match the run.' }
$project = "voucherflow-restore-$Run"
$files = @('deploy/compose.restore.yaml')
$compose = Get-ProjectComposeArguments -ComposeFiles $files -Project $project
$source = Get-ProjectComposeArguments -ComposeFiles @('compose.yaml', 'deploy/compose.container.yaml')
$priorRun = $env:VF_RECOVERY_RUN
$env:VF_RECOVERY_RUN = $Run

function Wait-TerminalOrder {
    param($Context, [string]$OrderId)
    $deadline = [datetime]::UtcNow.AddSeconds(120)
    do {
        $order = Invoke-VfApi $Context ("/api/admin/fulfillments/$OrderId")
        if ($order.fulfillmentStatus -in @('FULFILLED', 'FAILED')) { return $order }
        Start-Sleep -Seconds 1
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Restored order did not reach a terminal state: $OrderId"
}

try {
    Assert-RecoveryArchive $snapshot $manifest
    if ($Action -eq 'Prepare') {
        # 准备阶段导入两端快照，并逐表核对恢复数据。
        foreach ($service in @('mysql', 'supplier-db')) {
            $dump = Join-Path $snapshot "$service.sql"
            if ((Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash -ne $manifest.dumps.$service.sha256) {
                throw 'Recovery dump checksum mismatch.'
            }
        }
        docker @compose up -d --wait --wait-timeout 120 mysql supplier-db redis
        if ($LASTEXITCODE -ne 0) { throw 'Isolated recovery databases did not start.' }
        foreach ($service in @('mysql', 'supplier-db')) {
            $database = if ($service -eq 'mysql') { 'vf_restore_main' } else { 'vf_restore_supplier' }
            & (Join-Path $PSScriptRoot 'restore-local.ps1') -Service $service -Database $database `
                -Backup (Join-Path $snapshot "$service.sql") -ComposeFiles $files -Project $project
            $grant = @'
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
GRANT ALL PRIVILEGES ON $1.* TO '$MYSQL_USER'@'%';
SQL
'@
            docker @compose exec -T $service sh -c $grant sh $database
            if ($LASTEXITCODE -ne 0) { throw 'Cannot authorize the isolated application database account.' }
            $restored = Get-RecoveryDatabaseManifest $compose $service $database
            $expected = if ($service -eq 'mysql') { $manifest.main } else { $manifest.supplier }
            if ($restored.tables.Count -ne $expected.tables.Count) { throw 'Restored table inventory differs.' }
            foreach ($table in $expected.tables) {
                $actual = $restored.tables | Where-Object table -eq $table.table
                if (!$actual -or $actual.rows -ne $table.rows -or $actual.checksum -ne $table.checksum) {
                    throw "Restored table differs: $service/$($table.table)"
                }
            }
        }
        docker @compose up -d namesrv broker
        if ($LASTEXITCODE -ne 0) { throw 'Isolated recovery broker did not start.' }
        & (Join-Path $PSScriptRoot 'mq-bootstrap.ps1') -ComposeFiles $files -Project $project
        if ($LASTEXITCODE -ne 0) { throw 'Isolated recovery messaging bootstrap failed.' }
        [ordered]@{ run = $Run; mainTables = $manifest.main.tables.Count; supplierTables = $manifest.supplier.tables.Count; checksumsMatch = $true } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'prepared.json') -Encoding utf8NoBOM
    } elseif ($Action -eq 'Start') {
        # 启动阶段要求镜像与快照一致，再重放已发送但尚未消费的事件。
        if (!(Test-Path -LiteralPath (Join-Path $directory 'prepared.json'))) { throw 'Run Prepare successfully before Start.' }
        foreach ($service in @('app', 'supplier')) {
            $name = if ($service -eq 'app') { 'voucherflow:local' } else { 'voucherflow-supplier:local' }
            $image = docker image inspect --format '{{.Id}}' $name
            if ($LASTEXITCODE -ne 0 -or $image -ne $manifest.artifacts.$service.imageId) {
                throw "Restored $service image differs from the snapshot; this workflow requires the same version."
            }
        }
        docker @compose up -d supplier app
        if ($LASTEXITCODE -ne 0) { throw 'Isolated recovery services did not start.' }
        Wait-RecoveryHealth 'http://127.0.0.1:18090/actuator/health'
        Wait-RecoveryHealth 'http://127.0.0.1:18080/actuator/health/readiness'
        $artifacts = Get-RecoveryArtifacts $compose
        foreach ($service in @('app', 'supplier')) {
            if ($artifacts[$service].imageId -ne $manifest.artifacts.$service.imageId -or
                    $artifacts[$service].jarSha256 -ne $manifest.artifacts.$service.jarSha256) {
                throw "Restored $service artifact differs from the snapshot; this workflow requires the same version."
            }
        }
        $artifacts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $directory 'artifacts.json') -Encoding utf8NoBOM
        if ($manifest.unconsumedSentEvents -gt 0) {
            docker @compose exec -T app java -Duser.home=/app/.local -Dloader.main=com.example.voucherflow.outbox.DlqReplayTool `
                -cp /app/voucherflow.jar org.springframework.boot.loader.launch.PropertiesLauncher /recovery/sent-unconsumed.jsonl broker:8081
            if ($LASTEXITCODE -ne 0) { throw 'Isolated recovery replay failed; archive is preserved.' }
        }
        Write-Host 'Restored services are ready on the isolated network.'
    } else {
        # 验证阶段等待旧订单收敛，并用一笔新业务检查恢复环境可继续履约。
        $settings = Get-LocalSettings
        $base = 'http://127.0.0.1:18080'
        $admin = New-VfApiSession -BaseUrl $base -Username 'admin-demo' -Password $settings['VF_DEMO_ADMIN_PASSWORD']
        try {
            $results = @()
            # 旧终态必须保持不变；快照中的待处理订单应在恢复后完成。
            foreach ($old in $manifest.orders) {
                $order = Wait-TerminalOrder $admin $old.orderId
                if ($old.status -in @('FULFILLED', 'FAILED') -and $order.fulfillmentStatus -ne $old.status) {
                    throw 'Restored terminal order changed its business result.'
                }
                if ($old.orderId -eq $manifest.pendingOrderId -and $order.fulfillmentStatus -ne 'FULFILLED') {
                    throw 'The pending recovery fixture was not fulfilled.'
                }
                $results += [ordered]@{ orderId = $old.orderId; before = $old.status; beforeJobState = $old.jobState; after = $order.fulfillmentStatus }
            }
            $now = [datetime]::UtcNow
            $campaign = Invoke-VfApi $admin '/api/admin/campaigns' POST -ExpectedStatus 201 -Body @{
                name = "restored-$Run"; description = '恢复后新业务'; totalStock = 2
                startAt = $now.AddMinutes(-1).ToString('o'); endAt = $now.AddHours(1).ToString('o')
                redeemEndAt = $now.AddHours(2).ToString('o'); fulfillmentMode = 'SUPPLIER'
                supplierId = 'simulator'; productCode = 'restored'; contractVersion = 'v1'
            }
            $null = Invoke-VfApi $admin ('/api/admin/campaigns/' + $campaign.id + '/publish') POST
            $user = New-VfApiSession -BaseUrl $base -Username 'user-demo' -Password $settings['VF_DEMO_USER_PASSWORD']
            try { $newOrder = Invoke-VfApi $user ('/api/campaigns/' + $campaign.id + '/claim') POST -ExpectedStatus 202 }
            finally { Close-VfApiSession $user }
            $completed = Wait-TerminalOrder $admin $newOrder.orderId
            if ($completed.fulfillmentStatus -ne 'FULFILLED') { throw 'New business in the restored environment failed.' }
            $orders = @(Get-RecoveryOrders $compose vf_restore_main)
            $verified = @()
            foreach ($order in $orders) {
                $remote = Invoke-RestMethod ("http://127.0.0.1:18090/supplier/fulfillments/" + $order.requestNo) `
                    -Headers @{ Authorization = 'Bearer ' + $settings['VF_SUPPLIER_API_TOKEN'] } -TimeoutSec 5
                $expected = if ($order.status -eq 'FULFILLED') { 'SUCCEEDED' } elseif ($order.status -eq 'FAILED') { 'REJECTED' } else { throw 'Restored order remains unsettled.' }
                if ($remote.status -ne $expected -or $remote.requestNo -ne $order.requestNo) { throw 'Restored supplier and local order disagree.' }
                $id = [long]$order.orderId
                if ($order.status -eq 'FULFILLED') {
                    $hash = Invoke-RecoverySql $compose mysql vf_restore_main "SELECT SHA2(supplier_voucher_ref,256) FROM user_vouchers WHERE order_id=$id"
                    $remoteHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($remote.voucherRef))).ToLowerInvariant()
                    if ($hash -ne $remoteHash) { throw 'Restored supplier voucher identity differs.' }
                } else {
                    $count = Invoke-RecoverySql $compose mysql vf_restore_main "SELECT COUNT(*) FROM user_vouchers WHERE order_id=$id"
                    if ([long]$count -ne 0 -or $null -ne $remote.voucherRef) { throw 'Rejected order has a voucher.' }
                }
                $stats = Invoke-VfApi $admin ('/api/admin/campaigns/' + $order.campaignId + '/stats')
                if (!$stats.consistent) { throw 'Restored inventory or receipt linkage is inconsistent.' }
                $verified += [ordered]@{ orderId = $order.orderId; localStatus = $order.status; supplierStatus = $remote.status
                    voucherIdentityMatches = $true; accountingConsistent = $stats.consistent
                    total = $stats.totalStock; available = $stats.availableStock; reserved = $stats.reservedStock; issued = $stats.issuedStock }
            }
            $newRequest = ($orders | Where-Object orderId -eq $newOrder.orderId).requestNo
            if ($newRequest -notmatch '^[0-9a-f-]{36}$') { throw 'Unexpected restored request identity.' }
            $sourceMain = Invoke-RecoverySql $source mysql voucherflow "SELECT COUNT(*) FROM fulfillment_orders WHERE request_no='$newRequest'"
            $sourceSupplier = Invoke-RecoverySql $source supplier-db vf_supplier "SELECT COUNT(*) FROM supplier_requests WHERE request_no='$newRequest'"
            if ([long]$sourceMain -ne 0 -or [long]$sourceSupplier -ne 0) { throw 'Restored business reached a source database.' }
            [ordered]@{
                run = $Run; project = $project; oldOrders = $results; supplierChecks = $verified
                pendingOrderId = $manifest.pendingOrderId; newOrderId = $newOrder.orderId
                unconsumedEventsReplayed = $manifest.unconsumedSentEvents; sourceContainsNewRequest = $false
                artifacts = (Get-Content -LiteralPath (Join-Path $directory 'artifacts.json') -Raw | ConvertFrom-Json)
            } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $directory 'verification.json') -Encoding utf8NoBOM
            Write-Host "Verified restored supplier business: $directory"
        } finally { Close-VfApiSession $admin }
    }
} finally { $env:VF_RECOVERY_RUN = $priorRun }
