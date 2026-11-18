# 常规测试及消息、恢复、修复专项测试入口。
param([ValidateSet('Core', 'Messaging', 'Recovery', 'Repair')][string]$Suite = 'Core')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot

if ($Suite -eq 'Recovery') {
    foreach ($module in @('.', 'supplier-simulator')) {
        $exitCode = Invoke-ProjectMaven -MavenArguments @('-f', (Join-Path $module 'pom.xml'), '-DskipTests', 'package')
        if ($exitCode -ne 0) { exit $exitCode }
    }
}
if ($Suite -ne 'Core') {
    $tests = switch ($Suite) {
        'Messaging' { @('RocketMqBrokerCheck', 'OutboxBrokerFailureCheck') }
        'Recovery' { @('ProcessRecoveryCheck', 'CrossProcessCheck') }
        'Repair' { @('SupplierWorkflowIT#repairOverHttpUsesFreshEvidenceAndReplaysOriginalCommand') }
    }
    $started = [datetime]::UtcNow
    $exitCode = Invoke-ProjectMaven -MavenArguments @('test-compile', "-Dit.test=$($tests -join ',')", 'failsafe:integration-test', 'failsafe:verify')
    if ($exitCode -ne 0) { exit $exitCode }
    foreach ($test in $tests) {
        $null = Assert-ProjectTestReports -Directory (Join-Path $projectRoot 'target/failsafe-reports') `
            -Pattern "TEST-*$($test.Split('#')[0]).xml" -NotBeforeUtc $started
    }
    if ($Suite -eq 'Repair') {
        $result = Get-Item -LiteralPath (Join-Path $projectRoot 'target/repair-demo/result.json')
        if ($result.LastWriteTimeUtc -lt $started) { throw 'Repair evidence is stale.' }
        Get-Content -LiteralPath $result.FullName
    }
    exit 0
}

& (Join-Path $PSScriptRoot 'verify-load-records.ps1')
$total = 0
foreach ($module in @('.', 'supplier-simulator')) {
    $started = [datetime]::UtcNow
    $exitCode = Invoke-ProjectMaven -MavenArguments @('-f', (Join-Path $module 'pom.xml'), 'clean', 'verify')
    if ($exitCode -ne 0) { exit $exitCode }
    foreach ($reports in @('surefire-reports', 'failsafe-reports')) {
        $reportPath = Join-Path $projectRoot "$module/target/$reports"
        $total += Assert-ProjectTestReports -Directory $reportPath -NotBeforeUtc $started
    }
}
Write-Host "Verified $total tests, with no failures or skips."
exit 0
