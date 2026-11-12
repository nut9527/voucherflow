# 通过管理员 API 创建并发布示例活动，保留已有同名活动。
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-local.ps1')
. (Join-Path $PSScriptRoot 'api-session.ps1')
$settings = Get-LocalSettings
$admin = New-VfApiSession -BaseUrl $BaseUrl -Username 'admin-demo' -Password $settings.VF_DEMO_ADMIN_PASSWORD
try {
    $existing = @()
    $cursor = '0'
    do {
        $page = Invoke-VfApi -Context $admin -Path "/api/admin/campaigns?size=100&afterId=$cursor"
        $existing += $page.items
        $cursor = $page.nextCursor
    } while ($cursor)
    $offers = @(
        @{ name='城市咖啡礼遇'; description='一杯咖啡，为日常留一点香气。领取后出示权益编号，由工作人员核销。'; stock=50 }
        @{ name='周末观影计划'; description='把周末留给一场好电影。每个账户限领一份，使用前请确认有效期。'; stock=30 }
        @{ name='运动补给礼遇'; description='为热爱运动的你准备的补给权益。领取成功后可随时查看专属凭证。'; stock=20 }
    )
    foreach ($offer in $offers) {
        if ($existing.name -contains $offer.name) { Write-Host "Preserved existing campaign: $($offer.name)"; continue }
        $now = [DateTimeOffset]::UtcNow
        $body = @{
            name=$offer.name; description=$offer.description; totalStock=$offer.stock; fulfillmentMode='LOCAL'
            startAt=$now.AddMinutes(-5).ToString('o'); endAt=$now.AddDays(7).ToString('o'); redeemEndAt=$now.AddDays(30).ToString('o')
        }
        $created = Invoke-VfApi -Context $admin -Path '/api/admin/campaigns' -Method POST -Body $body -ExpectedStatus 201
        $id = $created.id
        $null = Invoke-VfApi -Context $admin -Path "/api/admin/campaigns/$id/publish" -Method POST
        Write-Host "Published #${id}: $($offer.name)"
    }
} finally {
    Close-VfApiSession -Context $admin
}
