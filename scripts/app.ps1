#requires -Version 5.1
param(
    [ValidateSet('start', 'stop')][string]$Action = 'start',
    [switch]$Faults
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-runtime.ps1')
. (Join-Path $PSScriptRoot 'compose-context.ps1')
$files = @('compose.yaml', 'deploy/compose.container.yaml')
$compose = @(Get-ProjectComposeArguments -ComposeFiles $files) + @('--profile', 'linux')

if ($Action -eq 'stop') {
    docker @compose stop --timeout 45
    exit $LASTEXITCODE
}

$dockerOs = docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0 -or $dockerOs -ne 'linux') {
    throw 'Start Docker Desktop with Linux containers.'
}
& (Join-Path $PSScriptRoot 'setup-local.ps1')
foreach ($module in @('.', 'supplier-simulator')) {
    $exitCode = Invoke-ProjectMaven -MavenArguments @(
        '-f', (Join-Path $module 'pom.xml'), '-Dmaven.test.skip=true', 'package'
    )
    if ($exitCode -ne 0) { exit $exitCode }
}

$previousFaults = [Environment]::GetEnvironmentVariable('VF_SUPPLIER_FAULTS_ENABLED', 'Process')
try {
    $env:VF_SUPPLIER_FAULTS_ENABLED = $Faults.IsPresent.ToString().ToLowerInvariant()
    docker @compose up -d --wait --wait-timeout 120 mysql redis supplier-db
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker @compose up -d namesrv broker
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Join-Path $PSScriptRoot 'mq-bootstrap.ps1') -ComposeFiles $files
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker @compose up -d --build supplier app
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    foreach ($url in @('http://localhost:8090/actuator/health', 'http://localhost:8080/actuator/health/readiness')) {
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
        do {
            try { $ready = (Invoke-RestMethod -Uri $url -TimeoutSec 3).status -eq 'UP' }
            catch { $ready = $false }
            if (!$ready) { Start-Sleep -Milliseconds 500 }
        } while (!$ready -and [DateTimeOffset]::UtcNow -lt $deadline)
        if (!$ready) { throw "Service not ready: $url" }
        Write-Host "$url UP"
    }
} finally {
    [Environment]::SetEnvironmentVariable('VF_SUPPLIER_FAULTS_ENABLED', $previousFaults, 'Process')
}
