# 关联领取采样记录与实际订单终态，并核对活动账本是否一致。
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,48}$')][string]$Run,
    [Parameter(Mandatory)][string]$Accounts,
    [Parameter(Mandatory)][string]$Outcomes,
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(5,180)][int]$WaitSeconds=30
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'api-session.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')
. (Join-Path $PSScriptRoot 'load-result-records.ps1')
$root=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (!([uri]$BaseUrl).IsLoopback) { throw 'Load collection is restricted to local project applications.' }
foreach ($path in @($Accounts,$Outcomes)) {
    $resolved=[IO.Path]::GetFullPath($path,$root)
    if (!$resolved.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Inputs must be inside the project.' }
}
$Accounts=[IO.Path]::GetFullPath($Accounts,$root)
$Outcomes=[IO.Path]::GetFullPath($Outcomes,$root)
$inputs=@(Get-Content -LiteralPath $Accounts -Raw | ConvertFrom-Json)
if ($inputs.Count -lt 1 -or $inputs.Count -gt 100) { throw 'Use 1–100 unique input combinations per collection.' }
$keys=[Collections.Generic.HashSet[string]]::new()
foreach ($fixture in $inputs) {
    if (!$fixture.username -or !$fixture.password -or [string]$fixture.campaignId -notmatch '^[1-9][0-9]*$' -or
        !$keys.Add("$($fixture.username):$($fixture.campaignId)")) { throw 'Invalid or duplicate input combination.' }
}
$records=Read-VfLoadRecords -Inputs $inputs -Lines @(Get-Content -LiteralPath $Outcomes) -Run $Run
$output=Join-Path $root ".local/perf/$Run/collection.json"
if (Test-Path -LiteralPath $output) { throw 'Collection already exists; preserve the original evidence.' }
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
$sessions=@{}
$results=[Collections.Generic.List[object]]::new()
$errors=[Collections.Generic.List[string]]::new()
$accounting=[Collections.Generic.List[object]]::new()
$settings=Get-LocalSettings
$admin=$null
$deadline=[DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds)
try {
    foreach ($row in $records.rows) {
        $fixture=$inputs[$row.fixtureIndex]
        $result=[pscustomobject]@{ fixtureIndex=$row.fixtureIndex; campaignId=$row.campaignId; requestState=$row.state;
            httpStatus=$row.response.httpStatus; code=$row.response.code; reportedOrderId=$row.response.orderId;
            orderId=$null; fulfillmentStatus='NOT_OBSERVED'; lookup='PENDING'; lookupAttempts=0; notFoundCount=0 }
        $results.Add($result)
        try {
            if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Collection deadline reached' }
            if (!$sessions.ContainsKey($fixture.username)) {
                $sessions[$fixture.username]=New-VfApiSession -BaseUrl $BaseUrl -Username $fixture.username -Password $fixture.password
            }
        } catch { $result.lookup='ERROR'; $errors.Add("Authentication or deadline failure at input $($row.fixtureIndex)") }
    }
    # 按输入组合查单，直到达到终态或耗尽本轮共享的收集期限。
    do {
        foreach ($result in $results) {
            if ($result.lookup -in @('ERROR','COMPLETE','NOT_STARTED')) { continue }
            if ([DateTimeOffset]::UtcNow -ge $deadline) { break }
            $fixture=$inputs[$result.fixtureIndex]
            $context=$sessions[$fixture.username]
            try {
                $result.lookupAttempts++
                $response=Invoke-WebRequest -Uri "$($context.BaseUrl)/api/me/fulfillments/by-campaign/$($result.campaignId)" -WebSession $context.Session -TimeoutSec 5 -SkipHttpErrorCheck
                if ($response.StatusCode -eq 404) {
                    $result.notFoundCount++
                    $result.lookup=if($result.requestState -eq 'NOT_STARTED'){'NOT_STARTED'}else{'NOT_OBSERVED'}
                    continue
                }
                if ($response.StatusCode -ne 200) { throw 'Order lookup failed' }
                $order=($response.Content | ConvertFrom-Json).data
                $result.orderId=$order.orderId; $result.fulfillmentStatus=$order.fulfillmentStatus
                $result.lookup=if($order.fulfillmentStatus -in @('FULFILLED','FAILED')){'COMPLETE'}else{'UNRESOLVED'}
                $original=$records.rows[$result.fixtureIndex].response.orderId
                if ($original -and $original -ne $order.orderId) { throw 'Order identity changed' }
                if ($result.requestState -eq 'NOT_STARTED') { throw 'An unstarted input already has an order' }
            } catch { $result.lookup='ERROR'; $errors.Add("Lookup or identity failure at input $($result.fixtureIndex)") }
        }
        $pending=@($results | Where-Object lookup -in @('PENDING','NOT_OBSERVED','UNRESOLVED'))
        if (!$pending.Count -or [DateTimeOffset]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 250
    } while ($true)
    # 订单结果之外还核对活动账本，确认库存与业务记录是否一致。
    $admin=New-VfApiSession -BaseUrl $BaseUrl -Username 'admin-demo' -Password $settings.VF_DEMO_ADMIN_PASSWORD
    foreach($campaign in @($inputs.campaignId | Sort-Object -Unique)) {
        $stats=Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$campaign/stats"
        $accounting.Add($stats)
        if (!$stats.consistent) { $errors.Add("Accounting differs for campaign $campaign") }
    }
} catch { $errors.Add('Collection did not finish; inspect application availability and input authentication.') }
finally {
    foreach ($context in $sessions.Values) { try { Close-VfApiSession -Context $context } catch { $errors.Add('Session cleanup failed') } }
    if ($admin) { try { Close-VfApiSession -Context $admin } catch { $errors.Add('Admin session cleanup failed') } }
    # 请求记录不全或仍有无法确认的订单时，结果标记为无法下结论。
    $incomplete=@($records.rows | Where-Object state -ne 'RESPONDED').Count -gt 0 -or $records.anomalies.Count -gt 0 -or $errors.Count -gt 0 -or
        @($results | Where-Object { Test-VfUnconfirmedLookup $_ }).Count -gt 0
    $report=@{ runId=$Run; inputCount=$inputs.Count; measurementComplete=(!$incomplete); result=if($incomplete){'INCONCLUSIVE'}else{'COLLECTED'};
        foundOrders=@($results | Where-Object orderId).Count; fulfilled=@($results | Where-Object fulfillmentStatus -eq 'FULFILLED').Count;
        failed=@($results | Where-Object fulfillmentStatus -eq 'FAILED').Count; unresolved=@($results | Where-Object fulfillmentStatus -in @('PENDING','UNKNOWN')).Count;
        notObserved=@($results | Where-Object fulfillmentStatus -eq 'NOT_OBSERVED').Count;
        anomalies=$records.anomalies; errors=@($errors.ToArray()); inputs=@($results.ToArray()); accounting=@($accounting.ToArray()) }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $output -Encoding utf8NoBOM
    Write-Output "Collection saved: $output; result=$($report.result)"
}
if ($incomplete) { exit 1 }
exit 0
