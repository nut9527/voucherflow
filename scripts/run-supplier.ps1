# 运行独立供应商模拟器，Faults 参数用于启用故障演示接口。
param([switch]$Faults)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')
$settings = Get-LocalSettings
$settings['VF_SUPPLIER_FAULTS_ENABLED'] = $Faults.IsPresent.ToString().ToLowerInvariant()
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeUser = Join-Path $projectRoot '.local/java-user'
$runtimeTemp = Join-Path $projectRoot '.local/tmp'
$arguments = @('-f','supplier-simulator/pom.xml','spring-boot:run',
    ('-Dspring-boot.run.jvmArguments=-Duser.home="{0}" -Djava.io.tmpdir="{1}"' -f $runtimeUser,$runtimeTemp))
exit (Invoke-ProjectMaven -MavenArguments $arguments -Environment $settings)
