# 依次验证采样记录、主应用和供应商模块，并汇总本轮完整测试结果。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
& (Join-Path $PSScriptRoot 'verify-load-records.ps1')

$projectRoot = Split-Path -Parent $PSScriptRoot
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
