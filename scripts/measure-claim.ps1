# 用独立账户与活动组合执行领取采样，并收集订单终态和账本结果。
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,48}$')][string]$Run,
    [Parameter(Mandatory)][string]$Accounts,
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(1,50)][int]$VirtualUsers=10,
    [ValidateRange(0,10)][int]$LoginSpacingSeconds=0
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$accountsPath=[IO.Path]::GetFullPath($Accounts,$root)
if (!$accountsPath.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Accounts must be inside the project.' }
$uri=[uri]$BaseUrl
if (!$uri.IsLoopback) { throw 'Measurements must target a local project application.' }
$inputs=@(Get-Content -LiteralPath $accountsPath -Raw | ConvertFrom-Json)
if($inputs.Count -lt 1 -or $inputs.Count -gt 100) { throw 'Use 1–100 unique input combinations.' }
$resultRoot=Join-Path $root ".local/perf/$Run"
if(Test-Path -LiteralPath $resultRoot) { throw 'Run already exists; choose a new run identifier.' }
New-Item -ItemType Directory -Path $resultRoot | Out-Null
$privateInput=Join-Path $resultRoot 'inputs.private.json'
$containerUrl=[UriBuilder]::new($uri)
$containerUrl.Host='host.docker.internal'
$image='grafana/k6:0.57.0@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b'
$loadExit=1
$collectionExit=1
try {
    # 仅在采样期间保存含凭证输入；对外结果清单只记录用户和活动标识。
    $inputs | ConvertTo-Json -AsArray | Set-Content -LiteralPath $privateInput -Encoding utf8NoBOM
    @($inputs | ForEach-Object -Begin { $index=0 } -Process {
        @{ runId=$Run; fixtureIndex=$index++; subject=$_.username; campaignId=[string]$_.campaignId }
    }) | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $resultRoot 'manifest.json') -Encoding utf8NoBOM
    docker run --rm --workdir /workspace `
        --mount "type=bind,source=$root/perf,target=/workspace/perf,readonly" `
        --mount "type=bind,source=$resultRoot,target=/workspace/.local/perf" `
        -e "VF_BASE_URL=$($containerUrl.Uri.AbsoluteUri.TrimEnd('/'))" -e "VF_RUN_ID=$Run" `
        -e VF_ACCOUNTS_FILE=/workspace/.local/perf/inputs.private.json -e "VF_VUS=$VirtualUsers" `
        -e "VF_LOGIN_SPACING=$LoginSpacingSeconds" `
        $image run --quiet --log-format raw --console-output /workspace/.local/perf/requests.jsonl `
        --out json=/workspace/.local/perf/samples.jsonl /workspace/perf/claim.js
    $loadExit=$LASTEXITCODE
    $outcomes=Join-Path $resultRoot 'requests.jsonl'
    if (!(Test-Path -LiteralPath $outcomes)) { [IO.File]::WriteAllText($outcomes,'') }
    # 无论采样是否成功，都用实际请求记录补查订单，避免丢失未知结果。
    & (Join-Path $PSScriptRoot 'collect-load-results.ps1') -Run $Run -Accounts $privateInput -Outcomes $outcomes -BaseUrl $BaseUrl
    $collectionExit=$LASTEXITCODE
} finally {
    if(Test-Path -LiteralPath $privateInput) { Remove-Item -LiteralPath $privateInput }
    @{ runId=$Run; image=$image; inputCount=$inputs.Count; virtualUsers=$VirtualUsers; baseUrl=$BaseUrl;
        loadExit=$loadExit; collectionExit=$collectionExit; sessionSetupIncluded=$false } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'metadata.json') -Encoding utf8NoBOM
}
if($loadExit -ne 0 -or $collectionExit -ne 0) { exit 1 }
exit 0
