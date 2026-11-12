# 读取项目 .env 中的 VF_ 配置项，供脚本显式传递给目标进程。
function Get-LocalSettings {
    $envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (!(Test-Path -LiteralPath $envPath)) { throw 'Run scripts/setup-local.ps1 first.' }
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        $line = $line.Trim()
        if (!$line -or $line.StartsWith('#')) { continue }
        if ($line -notmatch '^(VF_[A-Z0-9_]+)=(.*)$') { throw 'Invalid .env entry; expected VF_KEY=value.' }
        $settings[$Matches[1]] = $Matches[2]
    }
    return $settings
}
