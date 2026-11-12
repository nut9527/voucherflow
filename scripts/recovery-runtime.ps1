# 提供成对快照与恢复共用的制品识别、数据库核验和健康检查函数。
. (Join-Path $PSScriptRoot 'compose-context.ps1')

# 同时记录容器镜像和 JAR 摘要，供恢复端核对运行版本。
function Get-RecoveryArtifacts {
    param([string[]]$ComposeArguments)
    $artifacts = [ordered]@{}
    foreach ($service in @('app', 'supplier')) {
        $id = docker @ComposeArguments ps -q $service
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($id)) { throw "Missing running $service artifact." }
        $image = docker inspect --format '{{.Image}}' $id
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $service artifact." }
        $jar = if ($service -eq 'app') { '/app/voucherflow.jar' } else { '/app/supplier-simulator.jar' }
        $digest = docker @ComposeArguments exec -T $service sha256sum $jar
        if ($LASTEXITCODE -ne 0 -or $digest -notmatch '^([0-9a-f]{64})\s') { throw "Cannot hash $service artifact." }
        $artifacts[$service] = [ordered]@{ imageId = $image; jarSha256 = $Matches[1] }
    }
    return $artifacts
}

# 核对未消费事件归档的摘要与条数，确认其与快照清单一致。
function Assert-RecoveryArchive {
    param([string]$Directory, $Manifest)
    $archive = Join-Path $Directory 'sent-unconsumed.jsonl'
    if (!(Test-Path -LiteralPath $archive -PathType Leaf) -or !$Manifest.archive.sha256 -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Manifest.archive.sha256) {
        throw 'Recovery event archive is missing or its checksum differs.'
    }
    $lines = @(Get-Content -LiteralPath $archive)
    if ($lines.Count -ne $Manifest.unconsumedSentEvents -or $lines.Count -ne $Manifest.archive.count) {
        throw 'Recovery event archive count differs.'
    }
}

function Invoke-RecoverySql {
    param([string[]]$ComposeArguments, [string]$Service, [string]$Database, [string]$Sql)
    $result = $Sql | docker @ComposeArguments exec -T $Service sh -c `
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --binary-mode --local-infile=0 -uroot -N -B --raw -D "$1"' sh $Database
    if ($LASTEXITCODE -ne 0) { throw "Database verification failed for $Service/$Database" }
    return $result
}

# 汇总各表行数、内容校验值和迁移历史，作为恢复后的比对基准。
function Get-RecoveryDatabaseManifest {
    param([string[]]$ComposeArguments, [string]$Service, [string]$Database)
    $tables = @(Invoke-RecoverySql $ComposeArguments $Service $Database `
        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")
    $facts = @()
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-z0-9_]+$') { throw 'Unexpected table identifier in recovery scope.' }
        $count = Invoke-RecoverySql $ComposeArguments $Service $Database "SELECT COUNT(*) FROM $table"
        $checksum = Invoke-RecoverySql $ComposeArguments $Service $Database "CHECKSUM TABLE $table EXTENDED"
        $facts += [ordered]@{ table = $table; rows = [long]$count; checksum = ([string]$checksum).Split("`t")[1] }
    }
    $migrations = @(Invoke-RecoverySql $ComposeArguments $Service $Database `
        "SELECT JSON_OBJECT('version',version,'checksum',checksum,'success',success) FROM flyway_schema_history ORDER BY installed_rank" |
        ForEach-Object { $_ | ConvertFrom-Json })
    return [ordered]@{ tables = $facts; migrations = $migrations }
}

# 记录供应商订单的业务状态与任务状态，供恢复后的终态验证使用。
function Get-RecoveryOrders {
    param([string[]]$ComposeArguments, [string]$Database)
    $sql = @'
SELECT JSON_OBJECT('orderId',CAST(id AS CHAR),'campaignId',CAST(campaign_id AS CHAR),
 'requestNo',request_no,'status',status,'jobState',job_state,'revision',revision)
FROM fulfillment_orders WHERE fulfillment_mode='SUPPLIER' ORDER BY id
'@
    return @(Invoke-RecoverySql $ComposeArguments mysql $Database $sql | ForEach-Object { $_ | ConvertFrom-Json })
}

function Wait-RecoveryHealth {
    param([string]$Url)
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        try {
            $health = Invoke-RestMethod $Url -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "Recovery service did not become ready: $Url"
}
