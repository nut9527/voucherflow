# 构建两个应用制品，再验证进程恢复和跨进程业务协作。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')

# 恢复测试会启动独立进程，因此先完成两端 JAR 打包。
$exitCode = Invoke-ProjectMaven -MavenArguments @('-DskipTests', 'package')
if ($exitCode -ne 0) { exit $exitCode }
$exitCode = Invoke-ProjectMaven -MavenArguments @('-f', 'supplier-simulator/pom.xml', '-DskipTests', 'package')
if ($exitCode -ne 0) { exit $exitCode }

$started = [datetime]::UtcNow
$exitCode = Invoke-ProjectMaven -MavenArguments @('test-compile', '-Dit.test=ProcessRecoveryCheck,CrossProcessCheck', 'failsafe:integration-test', 'failsafe:verify')
if ($exitCode -ne 0) { exit $exitCode }
$reportPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/failsafe-reports'
$null = Assert-ProjectTestReports -Directory $reportPath -Pattern 'TEST-*ProcessRecoveryCheck.xml' -NotBeforeUtc $started
$null = Assert-ProjectTestReports -Directory $reportPath -Pattern 'TEST-*CrossProcessCheck.xml' -NotBeforeUtc $started
exit 0
