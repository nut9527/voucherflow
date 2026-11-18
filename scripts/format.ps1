# 使用两个模块的同一套规则格式化 Java；Check 模式只检查，不修改源码。
param([switch]$Check)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
$goal = if ($Check) { 'spotless:check' } else { 'spotless:apply' }
foreach ($module in @('.', 'supplier-simulator')) {
    $exitCode = Invoke-ProjectMaven -MavenArguments @('-f', (Join-Path $module 'pom.xml'), $goal)
    if ($exitCode -ne 0) { exit $exitCode }
}
exit 0
