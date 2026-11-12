# 收集本地应用健康状态、指定 JVM 与项目容器的诊断信息。
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(0, [int]::MaxValue)][int]$ApplicationProcessId = 0
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$destination = Join-Path $projectRoot ('.local/diagnostics/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$base = $BaseUrl.TrimEnd('/')
$failures = [Collections.Generic.List[string]]::new()

foreach ($probe in @('liveness', 'readiness')) {
    try {
        Invoke-RestMethod "$base/actuator/health/$probe" -TimeoutSec 5 |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination "$probe.json") -Encoding utf8
    } catch { $failures.Add("Health probe unavailable: $probe") }
}

# 指定进程时先确认其属于当前项目，再采集线程、堆与 JVM 参数。
if ($ApplicationProcessId -gt 0) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ApplicationProcessId"
    if (!$process -or !$process.CommandLine -or
            $process.CommandLine.IndexOf($projectRoot, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'The specified JVM cannot be verified as belonging to this project.'
    }
    $jcmd = Get-Command jcmd -ErrorAction Stop
    foreach ($command in @('VM.version', 'VM.flags', 'Thread.print', 'GC.heap_info')) {
        $diagnostic = Start-Process -FilePath $jcmd.Source -ArgumentList @($ApplicationProcessId, $command) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $destination ($command + '.txt')) `
            -RedirectStandardError (Join-Path $destination ($command + '.error.txt'))
        if (!$diagnostic.WaitForExit(10000)) {
            $diagnostic.Kill()
            $failures.Add("JVM diagnostic timed out: $command")
        } elseif ($diagnostic.ExitCode -ne 0) { $failures.Add("JVM diagnostic failed: $command") }
    }
}

Push-Location $projectRoot
try {
    $containerIds = @(docker compose --env-file .env ps -q)
    if ($LASTEXITCODE -eq 0 -and $containerIds.Count -gt 0) {
        docker stats --no-stream --format '{{json .}}' @containerIds |
            Set-Content -LiteralPath (Join-Path $destination 'containers.jsonl') -Encoding utf8
        if ($LASTEXITCODE -ne 0) { $failures.Add('Container resource statistics unavailable') }
    } else { $failures.Add('No running project containers were found') }
} finally { Pop-Location }

# 保留已成功采集的材料，同时记录缺失项供排查诊断是否完整。
$failures | Set-Content -LiteralPath (Join-Path $destination 'collection-errors.txt') -Encoding utf8
Write-Host "Diagnostics: $destination"
if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Warning $failure }
    exit 1
}
