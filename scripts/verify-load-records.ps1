# 验证采样记录归并保留全部输入，并识别重复、跨批次与未知结果。
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'load-result-records.ps1')
$inputs=@(@{campaignId='1'},@{campaignId='2'},@{campaignId='3'},@{campaignId='4'},@{campaignId='5'})
# 样本包含正常响应、传输失败、结果未知、中途退出和未启动五种情况。
$lines=@(
    '{"runId":"check","fixtureIndex":0,"campaignId":"1","kind":"START"}'
    '{"runId":"check","fixtureIndex":0,"campaignId":"1","kind":"RESULT","httpStatus":201,"code":"OK"}'
    '{"runId":"check","fixtureIndex":1,"campaignId":"2","kind":"START"}'
    '{"runId":"check","fixtureIndex":1,"campaignId":"2","kind":"RESULT","httpStatus":0,"code":"TRANSPORT_ERROR"}'
    '{"runId":"check","fixtureIndex":2,"campaignId":"3","kind":"START"}'
    '{"runId":"check","fixtureIndex":2,"campaignId":"3","kind":"RESULT","httpStatus":503,"code":"RESULT_UNKNOWN"}'
    '{"runId":"check","fixtureIndex":3,"campaignId":"4","kind":"START"}'
)
$result=Read-VfLoadRecords -Inputs $inputs -Lines $lines -Run check
if(($result.rows.state -join ',') -ne 'RESPONDED,RESPONDED,RESPONDED,STARTED_NO_RESULT,NOT_STARTED' -or $result.anomalies.Count) {
    throw 'Response loss, interrupted request or unstarted input disappeared from the denominator.'
}
$bad=Read-VfLoadRecords -Inputs $inputs -Lines ($lines+@($lines[0],'malformed')) -Run check
if($bad.anomalies.Count -ne 2) { throw 'Duplicate or malformed records were accepted.' }
$foreign=Read-VfLoadRecords -Inputs $inputs -Lines @('{"runId":"another","fixtureIndex":0,"campaignId":"1","kind":"START"}') -Run check
if($foreign.anomalies.Count -ne 1 -or $foreign.rows[0].state -ne 'NOT_STARTED') { throw 'Cross-run records contaminated the result.' }
$empty=Read-VfLoadRecords -Inputs $inputs -Lines @() -Run check
if(@($empty.rows | Where-Object state -eq 'NOT_STARTED').Count -ne 5) { throw 'An empty outcome file lost the input population.' }
$missing=Read-VfLoadRecords -Inputs $inputs -Lines @($lines[0],'{"runId":"check","fixtureIndex":0,"campaignId":"1","kind":"RESULT"}') -Run check
if($missing.anomalies.Count -ne 1 -or $missing.rows[0].state -ne 'STARTED_NO_RESULT') { throw 'Incomplete response fields were accepted.' }
foreach($status in @(0,201,202)) {
    if(!(Test-VfUnconfirmedLookup @{lookup='NOT_OBSERVED';httpStatus=$status;code='OK';reportedOrderId=$null})) {
        throw 'An unverified accepted/unknown request was classified complete.'
    }
}
if(Test-VfUnconfirmedLookup @{lookup='NOT_OBSERVED';httpStatus=429;code='RATE_LIMITED';reportedOrderId=$null}) {
    throw 'A definite rejection was classified as an unknown submission.'
}
if(Test-VfUnconfirmedLookup @{lookup='COMPLETE';httpStatus=201;code='OK';reportedOrderId='1'}) {
    throw 'A recovered order was classified as unverified.'
}
Write-Output 'Load record checks passed: responses, unknown outcomes, interruption, unstarted inputs, duplicates and cross-run isolation.'
