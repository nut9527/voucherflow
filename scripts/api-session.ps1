# 封装演示与验证脚本共用的登录会话、CSRF 请求和退出操作。
function New-VfApiSession {
    param([string]$BaseUrl, [string]$Username, [string]$Password)
    $base = $BaseUrl.TrimEnd('/')
    $token = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable session -TimeoutSec 10
    $null = Invoke-RestMethod "$base/api/auth/login" -Method Post -WebSession $session -TimeoutSec 10 `
        -Headers @{ $token.data.headerName = $token.data.token } -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ username=$Username; password=$Password }
    # 登录会更新安全上下文，因此重新获取供后续写请求使用的 CSRF 令牌。
    $token = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $session -TimeoutSec 10
    return [pscustomobject]@{ BaseUrl=$base; Session=$session; Headers=@{ $token.data.headerName=$token.data.token } }
}

# 复用会话发送业务请求，校验 HTTP 状态后提取统一响应中的 data。
function Invoke-VfApi {
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET','POST','PUT')][string]$Method = 'GET',
        [hashtable]$Body,
        [int]$ExpectedStatus = 200
    )
    $request = @{ Uri=$Context.BaseUrl+$Path; Method=$Method; WebSession=$Context.Session; TimeoutSec=10 }
    if ($Method -ne 'GET') { $request.Headers = $Context.Headers }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
    }
    $response = Invoke-WebRequest @request
    if ($response.StatusCode -ne $ExpectedStatus) { throw "${Method} ${Path}: expected $ExpectedStatus, got $($response.StatusCode)" }
    return ($response.Content | ConvertFrom-Json).data
}

function Close-VfApiSession {
    param([Parameter(Mandatory)]$Context)
    $null = Invoke-VfApi -Context $Context -Path '/api/auth/logout' -Method POST
}
