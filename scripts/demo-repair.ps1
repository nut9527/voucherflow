# 运行 HTTP 修复验收场景，验证新取证修复和同一命令的幂等回放。
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
$started=[datetime]::UtcNow
$exitCode=Invoke-ProjectMaven -MavenArguments @('test-compile','-Dit.test=SupplierWorkflowIT#repairOverHttpUsesFreshEvidenceAndReplaysOriginalCommand','failsafe:integration-test','failsafe:verify')
if($exitCode -ne 0) { exit $exitCode }
$projectRoot=Split-Path -Parent $PSScriptRoot
$null=Assert-ProjectTestReports -Directory (Join-Path $projectRoot 'target/failsafe-reports') -Pattern 'TEST-*SupplierWorkflowIT.xml' -NotBeforeUtc $started
$resultPath=Join-Path $projectRoot 'target/repair-demo/result.json'
if(!(Test-Path -LiteralPath $resultPath) -or (Get-Item -LiteralPath $resultPath).LastWriteTimeUtc -lt $started) {
    throw 'Repair verification did not produce a fresh result.'
}
Get-Content -LiteralPath $resultPath
Write-Host "Repair evidence: $resultPath"
