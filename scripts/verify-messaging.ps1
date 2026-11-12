# 运行真实消息代理与发布故障场景，并确认本轮生成的测试报告有效。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
$started = [datetime]::UtcNow
$exitCode = Invoke-ProjectMaven -MavenArguments @('test-compile', '-Dit.test=RocketMqBrokerCheck,OutboxBrokerFailureCheck', 'failsafe:integration-test', 'failsafe:verify')
if ($exitCode -ne 0) { exit $exitCode }
$reportPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/failsafe-reports'
$null = Assert-ProjectTestReports -Directory $reportPath -Pattern 'TEST-*RocketMqBrokerCheck.xml' -NotBeforeUtc $started
$null = Assert-ProjectTestReports -Directory $reportPath -Pattern 'TEST-*OutboxBrokerFailureCheck.xml' -NotBeforeUtc $started
exit 0
