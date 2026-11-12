# 导出死信报文到项目内归档，或保留原事件标识重放已有归档。
param(
    [Parameter(Mandatory)][ValidateSet('Export','Replay')][string]$Action,
    [ValidateSet('vf_fulfillment_worker','vf_notification_worker')][string]$Group = 'vf_notification_worker',
    [Parameter(Mandatory)][string]$Archive,
    [long]$BeginEpochMillis = ([DateTimeOffset]::UtcNow.AddDays(-1).ToUnixTimeMilliseconds()),
    [long]$EndEpochMillis = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()),
    [string]$Endpoints = '127.0.0.1:8081'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$archivePath = [IO.Path]::GetFullPath((Join-Path $projectRoot $Archive))
if (!$archivePath.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Archive must be inside this project.'
}
Push-Location $projectRoot
try {
    if ($Action -eq 'Export') {
        if (Test-Path -LiteralPath $archivePath) { throw 'Archive already exists; choose a new path.' }
        if ($BeginEpochMillis -ge $EndEpochMillis) { throw 'Archive range must have a positive duration.' }
        $output = @(docker compose exec -T broker sh mqadmin printMsg -n namesrv:9876 -t "%DLQ%$Group" -d true -c UTF-8 -b $BeginEpochMillis -e $EndEpochMillis 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -match 'Exception|command failed') { throw 'Dead-letter export failed.' }
        # 保存原报文及其摘要，使后续重放可以核验归档内容。
        $records = @($output | ForEach-Object {
            if ($_ -match '^MSGID: (\S+) .* BODY: (.*)$') {
                $body = $Matches[2]
                $record = [ordered]@{
                    consumerGroup = $Group
                    messageId = $Matches[1]
                    body = $body
                    bodySha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($body))).ToLowerInvariant()
                    archivedAt = [DateTimeOffset]::UtcNow.ToString('o')
                }
                $record | ConvertTo-Json -Compress
            }
        })
        if (!$records.Count -or $records.Count -gt 1000) { throw 'Export must contain 1–1000 records; narrow the range if necessary.' }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archivePath) | Out-Null
        [IO.File]::WriteAllLines($archivePath, [string[]]$records, [Text.UTF8Encoding]::new($false))
        Write-Host "Archived $($records.Count) messages to $archivePath. Consumer offsets are unchanged."
    } else {
        # 重放入口复用归档中的事件身份，由业务消费去重处理重复投递。
        if (!(Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw 'Archive does not exist.' }
        $runtimeUser = Join-Path $projectRoot '.local/java-user'
        $runtimeTemp = Join-Path $projectRoot '.local/tmp'
        $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
        & $java "-Duser.home=$runtimeUser" "-Djava.io.tmpdir=$runtimeTemp" '-Dloader.main=com.example.voucherflow.outbox.DlqReplayTool' `
            -cp (Join-Path $projectRoot 'target/voucherflow.jar') org.springframework.boot.loader.launch.PropertiesLauncher $archivePath $Endpoints
        if ($LASTEXITCODE -ne 0) { throw 'Replay failed; the archive is preserved. Retrying keeps the original event identities.' }
    }
} finally { Pop-Location }
