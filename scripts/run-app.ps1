# 加载本地配置运行主应用，可按参数切换缓存、限流和供应商履约。
param([ValidateRange(1, 65535)][int]$Port = 8080,
    [bool]$CacheEnabled = $true, [bool]$RateLimitEnabled = $true, [switch]$Supplier)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
. (Join-Path $PSScriptRoot 'load-local.ps1')

$applicationEnvironment = Get-LocalSettings
$applicationEnvironment['VF_CACHE_ENABLED'] = $CacheEnabled.ToString().ToLowerInvariant()
$applicationEnvironment['VF_RATE_LIMIT_ENABLED'] = $RateLimitEnabled.ToString().ToLowerInvariant()
if ($Supplier) {
    $applicationEnvironment['VF_SUPPLIER_ENABLED'] = 'true'
    $applicationEnvironment['VF_MESSAGING_ENABLED'] = 'true'
    $applicationEnvironment['VF_MESSAGING_PUBLISHING_ENABLED'] = 'true'
}

$tempPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.local/tmp'
$applicationUserPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.local/java-user'
$mavenArguments = @(
    'spring-boot:run'
    "-Dspring-boot.run.arguments=--server.port=$Port --spring.profiles.active=local"
    ('-Dspring-boot.run.jvmArguments=-Djava.io.tmpdir="{0}" -Duser.home="{1}"' -f $tempPath, $applicationUserPath)
)
exit (Invoke-ProjectMaven -MavenArguments $mavenArguments -Environment $applicationEnvironment)
