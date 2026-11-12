# 演示供应商响应丢失后的查单恢复，以及明确拒绝后的库存释放。
param([string]$BaseUrl='http://localhost:8080',[string]$SupplierUrl='http://localhost:8090')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'load-local.ps1')
. (Join-Path $PSScriptRoot 'api-session.ps1')
$settings=Get-LocalSettings
$supplierHeaders=@{ Authorization='Bearer '+$settings.VF_SUPPLIER_API_TOKEN }
$admin=New-VfApiSession -BaseUrl $BaseUrl -Username 'admin-demo' -Password $settings.VF_DEMO_ADMIN_PASSWORD
$user=$null
function Set-SupplierFaults([bool]$Drop,[bool]$Pause) {
    $body=@{ dropNextResponse=$Drop;pauseCallbacks=$Pause } | ConvertTo-Json
    $null=Invoke-RestMethod ($SupplierUrl.TrimEnd('/')+'/supplier/faults') -Method Put -Headers $supplierHeaders -ContentType 'application/json' -Body $body -TimeoutSec 10
}
function New-SupplierCampaign([string]$Product) {
    $now=[DateTimeOffset]::UtcNow
    $created=Invoke-VfApi -Context $admin -Path '/api/admin/campaigns' -Method POST -ExpectedStatus 201 -Body @{
        name='供应商履约-'+[Guid]::NewGuid().ToString('N').Substring(0,8);description='供应商幂等、查询恢复与库存核对'
        totalStock=1;fulfillmentMode='SUPPLIER';supplierId='simulator';productCode=$Product;contractVersion='v1'
        startAt=$now.AddMinutes(-1).ToString('o');endAt=$now.AddHours(1).ToString('o');redeemEndAt=$now.AddDays(1).ToString('o')
    }
    $null=Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$($created.id)/publish" -Method POST
    return $created.id
}
# 轮询并记录途经状态，既验证终态，也验证响应丢失时出现 UNKNOWN。
function Wait-Fulfillment([string]$Campaign,[string]$Expected) {
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(120)
    $states=[Collections.Generic.HashSet[string]]::new()
    do {
        $order=Invoke-VfApi -Context $user -Path "/api/me/fulfillments/by-campaign/$Campaign"
        $null=$states.Add($order.fulfillmentStatus)
        if($order.fulfillmentStatus -eq $Expected) { return @{ Order=$order;States=@($states | ForEach-Object { $_ }) } }
        if($order.fulfillmentStatus -in @('FULFILLED','FAILED')) { throw "Unexpected terminal status: $($order.fulfillmentStatus)" }
        Start-Sleep -Milliseconds 250
    } while([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Fulfillment did not reach $Expected; observed $($states -join ',')."
}
try {
    $user=New-VfApiSession -BaseUrl $BaseUrl -Username 'user-demo' -Password $settings.VF_DEMO_USER_PASSWORD
    # 同时暂停回调并丢失一次响应，让主应用通过主动查单恢复发放结果。
    Set-SupplierFaults -Drop $true -Pause $true
    $success=New-SupplierCampaign -Product 'course'
    $accepted=Invoke-VfApi -Context $user -Path "/api/campaigns/$success/claim" -Method POST -ExpectedStatus 202
    $recovered=Wait-Fulfillment -Campaign $success -Expected 'FULFILLED'
    if($recovered.States -notcontains 'UNKNOWN') { throw 'UNKNOWN was not observed during response-loss recovery.' }
    $replay=Invoke-VfApi -Context $user -Path "/api/campaigns/$success/claim" -Method POST
    if($replay.orderId -ne $accepted.orderId -or !$replay.replayed) { throw 'Claim replay changed the order.' }
    $confirmed=Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$success/stats"
    if(!$confirmed.consistent -or $confirmed.issuedStock -ne 1 -or $confirmed.reservedStock -ne 0) { throw 'Success inventory did not reconcile.' }
    # 商品 reject 触发供应商明确拒绝，用于验证只释放一次预留库存。
    Set-SupplierFaults -Drop $false -Pause $true
    $rejected=New-SupplierCampaign -Product 'reject'
    $null=Invoke-VfApi -Context $user -Path "/api/campaigns/$rejected/claim" -Method POST -ExpectedStatus 202
    $failed=Wait-Fulfillment -Campaign $rejected -Expected 'FAILED'
    $released=Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$rejected/stats"
    if(!$released.consistent -or $released.availableStock -ne 1 -or $released.reservedStock -ne 0 -or $released.issuedStock -ne 0) { throw 'Rejected fulfillment did not release exactly one reservation.' }
    [pscustomobject]@{ RecoveredOrder=$accepted.orderId;ObservedStates=$recovered.States;FailedOrder=$failed.Order.orderId;Confirmed=$confirmed.consistent;Released=$released.consistent } | ConvertTo-Json
} finally {
    try { Set-SupplierFaults -Drop $false -Pause $false }
    finally {
        if($null -ne $user) { Close-VfApiSession -Context $user }
        Close-VfApiSession -Context $admin
    }
}
