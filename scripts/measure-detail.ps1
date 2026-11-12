# 记录活动详情的预热与正式采样结果，以及正式采样期间的数据库读取增量。
param(
    [Parameter(Mandatory)][ValidateSet('baseline', 'cached')][string]$Variant,
    [Parameter(Mandatory)][ValidateRange(1, 3)][int]$Round,
    [Parameter(Mandatory)][ValidatePattern('^[1-9][0-9]*(,[1-9][0-9]*)*$')][string]$CampaignIds,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,48}$')][string]$Run,
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'api-session.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$uri = [uri]$BaseUrl
if (!$uri.IsLoopback) { throw 'Performance measurements must target a local project application.' }
$containerUrl = [UriBuilder]::new($uri)
$containerUrl.Host = 'host.docker.internal'
$resultRoot = Join-Path $projectRoot ".local/perf/$Run/$Variant-$Round"
if (Test-Path -LiteralPath $resultRoot) { throw 'This measurement already exists; choose a new run name.' }
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$image = 'grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b'
$settings = Get-LocalSettings
$context = New-VfApiSession -BaseUrl $BaseUrl -Username 'admin-demo' -Password $settings['VF_DEMO_ADMIN_PASSWORD']

# 读取缓存回源计数器，前后差值表示正式采样增加的数据库读取次数。
function Get-DatabaseReads {
    $result = Invoke-RestMethod ($BaseUrl.TrimEnd('/') + '/actuator/metrics/voucherflow.cache.database.reads') `
        -WebSession $context.Session -TimeoutSec 5
    return [double]($result.measurements | Where-Object statistic -eq 'COUNT').value
}

function Invoke-DetailLoad {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$Duration)
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    docker run --rm --workdir /workspace `
        --mount "type=bind,source=$projectRoot/perf,target=/workspace/perf,readonly" `
        --mount "type=bind,source=$Directory,target=/workspace/.local/perf" `
        -e "VF_BASE_URL=$($containerUrl.Uri.AbsoluteUri.TrimEnd('/'))" -e "VF_CAMPAIGN_IDS=$CampaignIds" `
        -e VF_RATE=50 -e "VF_DURATION=$Duration" -e VF_VUS=10 -e VF_MAX_VUS=50 `
        $image run --quiet --out json=/workspace/.local/perf/samples.jsonl /workspace/perf/detail.js
    if ($LASTEXITCODE -ne 0) { throw 'k6 measurement failed; inspect its result and exit code.' }
}

try {
    # 预热输出单独保存，正式测量的延迟和回源计数从预热结束后开始。
    Invoke-DetailLoad -Directory (Join-Path $resultRoot 'warmup') -Duration '15s'
    $before = Get-DatabaseReads
    Invoke-DetailLoad -Directory (Join-Path $resultRoot 'measurement') -Duration '60s'
    $after = Get-DatabaseReads
    @{
        variant = $Variant; round = $Round; baseUrl = $BaseUrl; campaignIds = $CampaignIds
        image = $image; warmup = '15s'; duration = '60s'; rate = 50; preallocatedVus = 10; maxVus = 50
        databaseReadsBefore = $before; databaseReadsAfter = $after; databaseReads = $after - $before
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'metadata.json') -Encoding utf8NoBOM
    Write-Host "Measurement recorded: $resultRoot"
} finally { Close-VfApiSession -Context $context }
