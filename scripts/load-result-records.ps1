# 将领取采样日志还原为每个输入的请求状态，保留缺失与异常记录。
function Read-VfLoadRecords {
    param([Parameter(Mandatory)][object[]]$Inputs, [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Lines,
        [Parameter(Mandatory)][string]$Run)
    # 先为全部输入建档，使未启动与中途退出的请求仍纳入采样总数。
    $rows = @($Inputs | ForEach-Object -Begin { $index = 0 } -Process {
        [pscustomobject]@{ fixtureIndex=$index++; campaignId=[string]$_.campaignId; state='NOT_STARTED'; response=$null }
    })
    $anomalies = [Collections.Generic.List[string]]::new()
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $record = $line | ConvertFrom-Json -ErrorAction Stop }
        catch { $anomalies.Add('Malformed outcome record'); continue }
        $index = 0
        if ($record.runId -ne $Run -or ![int]::TryParse([string]$record.fixtureIndex, [ref]$index) -or
                $index -lt 0 -or $index -ge $rows.Count) { $anomalies.Add('Unknown run or fixture index'); continue }
        $row = $rows[$index]
        if ([string]$record.campaignId -ne $row.campaignId) { $anomalies.Add("Campaign mismatch at $index"); continue }
        # 仅接受 START 后跟 RESULT 的状态顺序，重复与乱序记录另行保留为异常。
        if ($record.kind -eq 'START' -and $row.state -eq 'NOT_STARTED') { $row.state='STARTED_NO_RESULT' }
        elseif ($record.kind -eq 'RESULT' -and $row.state -eq 'STARTED_NO_RESULT') {
            $status = 0
            if (![int]::TryParse([string]$record.httpStatus, [ref]$status) -or
                    ($status -ne 0 -and ($status -lt 100 -or $status -gt 599)) -or [string]::IsNullOrWhiteSpace($record.code)) {
                $anomalies.Add("Incomplete response at $index")
                continue
            }
            $row.state='RESPONDED'; $row.response=$record
        } else { $anomalies.Add("Duplicate or unordered record at $index") }
    }
    return [pscustomobject]@{ rows=$rows; anomalies=@($anomalies.ToArray()) }
}

# 未查到订单时，区分明确拒绝与可能已受理但尚未确认的请求。
function Test-VfUnconfirmedLookup {
    param([Parameter(Mandatory)]$Result)
    return $Result.lookup -eq 'PENDING' -or ($Result.lookup -eq 'NOT_OBSERVED' -and
        ($Result.httpStatus -in @(0,200,201,202) -or $Result.code -eq 'RESULT_UNKNOWN' -or $Result.reportedOrderId))
}
