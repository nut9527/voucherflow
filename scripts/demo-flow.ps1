# 通过 HTTP 演示本地发放、重复领取、幂等核销与库存对账的完整流程。
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-local.ps1')
. (Join-Path $PSScriptRoot 'api-session.ps1')
$settings = Get-LocalSettings
$admin = New-VfApiSession -BaseUrl $BaseUrl -Username 'admin-demo' -Password $settings.VF_DEMO_ADMIN_PASSWORD
$user = $null
try {
    $user = New-VfApiSession -BaseUrl $BaseUrl -Username 'user-demo' -Password $settings.VF_DEMO_USER_PASSWORD
    $now = [DateTimeOffset]::UtcNow
    $campaign = Invoke-VfApi -Context $admin -Path '/api/admin/campaigns' -Method POST -ExpectedStatus 201 -Body @{
        name='API闭环-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
        description='通过真实HTTP验证领取、核销与库存对账'; totalStock=20; fulfillmentMode='LOCAL'
        startAt=$now.AddMinutes(-1).ToString('o'); endAt=$now.AddHours(1).ToString('o'); redeemEndAt=$now.AddDays(1).ToString('o')
    }
    $id = $campaign.id
    $null = Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$id/publish" -Method POST
    # 同一用户连续领取两次，第二次应复用原订单和原券。
    $first = Invoke-VfApi -Context $user -Path "/api/campaigns/$id/claim" -Method POST -ExpectedStatus 201
    $repeat = Invoke-VfApi -Context $user -Path "/api/campaigns/$id/claim" -Method POST
    if ($first.orderId -ne $repeat.orderId -or $first.voucherId -ne $repeat.voucherId -or !$repeat.replayed) {
        throw 'Repeated claim did not return the original order and voucher.'
    }
    $order = Invoke-VfApi -Context $user -Path "/api/me/fulfillments/by-campaign/$id"
    $voucher = Invoke-VfApi -Context $user -Path "/api/me/vouchers/$($first.voucherId)"
    if ($order.orderId -ne $first.orderId -or $voucher.id -ne $first.voucherId) { throw 'Owned record lookup mismatch.' }
    # 管理员重复核销同一张券，两次都应返回首次生成的回执。
    $receipt = Invoke-VfApi -Context $admin -Path "/api/vouchers/$($first.voucherId)/redeem" -Method POST
    $again = Invoke-VfApi -Context $admin -Path "/api/vouchers/$($first.voucherId)/redeem" -Method POST
    if ($receipt.id -ne $again.id -or $receipt.createdAt -ne $again.createdAt -or $receipt.operatorId -ne $again.operatorId -or !$again.replayed) {
        throw 'Repeated redemption did not preserve the original receipt.'
    }
    # 业务闭环结束后检查预留归零，以及已发放库存和核销数量。
    $stats = Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$id/stats"
    if (!$stats.consistent -or $stats.availableStock -ne 19 -or $stats.reservedStock -ne 0 -or $stats.issuedStock -ne 1 -or $stats.redemptionCount -ne 1) {
        throw 'Inventory reconciliation failed.'
    }
    [pscustomobject]@{
        CampaignId=$id; OrderId=$first.orderId; VoucherId=$first.voucherId; ReceiptId=$receipt.id
        Available=$stats.availableStock; Reserved=$stats.reservedStock; Issued=$stats.issuedStock
        ClaimReplay=$repeat.replayed; RedemptionReplay=$again.replayed; Consistent=$stats.consistent
    } | ConvertTo-Json
} finally {
    if ($null -ne $user) { Close-VfApiSession -Context $user }
    Close-VfApiSession -Context $admin
}
