# 依次启动数据库、消息服务、供应商与主应用，并等待应用就绪。
param([switch]$Build,[switch]$Faults)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$composeArguments=@('compose','--env-file','.env','-f','compose.yaml','-f','deploy/compose.container.yaml','--profile','linux')
$previousFaults=[Environment]::GetEnvironmentVariable('VF_SUPPLIER_FAULTS_ENABLED','Process')
Push-Location $projectRoot
try {
    $env:VF_SUPPLIER_FAULTS_ENABLED=$Faults.IsPresent.ToString().ToLowerInvariant()
    & docker @composeArguments up -d --wait --wait-timeout 120 mysql redis supplier-db
    if($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & docker @composeArguments up -d namesrv broker
    if($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # 业务应用启动前先准备消息主题与消费者组。
    & (Join-Path $PSScriptRoot 'mq-bootstrap.ps1')
    if($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    [string[]]$buildArguments=if($Build) { @('--build') } else { @() }
    & docker @composeArguments up -d @buildArguments supplier app
    if($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(120)
    foreach($endpoint in @('http://127.0.0.1:8090/actuator/health','http://127.0.0.1:8080/actuator/health/readiness')) {
        $ready=$false
        do {
            try { $ready=(Invoke-RestMethod -Uri $endpoint -TimeoutSec 3).status -eq 'UP' }
            catch { $ready=$false }
            if(!$ready) { Start-Sleep -Milliseconds 500 }
        } while(!$ready -and [DateTimeOffset]::UtcNow -lt $deadline)
        if(!$ready) { throw "Container endpoint did not become ready: $endpoint" }
    }
    Write-Host 'Application endpoints are ready. Run demo-flow.ps1 and demo-supplier.ps1 to verify business processing.'
} finally {
    [Environment]::SetEnvironmentVariable('VF_SUPPLIER_FAULTS_ENABLED',$previousFaults,'Process')
    Pop-Location
}
