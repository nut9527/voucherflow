# 将 Maven 仓库和临时目录限定在项目内，并统一校验测试报告。
function Invoke-ProjectMaven {
    param(
        [Parameter(Mandatory)][string[]]$MavenArguments,
        [hashtable]$Environment = @{}
    )

    $projectRoot = Split-Path -Parent $PSScriptRoot
    $localRoot = Join-Path $projectRoot '.local'
    $runtime = @{
        MAVEN_USER_HOME = Join-Path $localRoot 'maven-user'
        TEMP = Join-Path $localRoot 'tmp'
        TMP = Join-Path $localRoot 'tmp'
    }
    $repository = Join-Path $localRoot 'm2'
    $javaHome = Join-Path $localRoot 'java-user'
    New-Item -ItemType Directory -Force -Path $repository, $runtime.MAVEN_USER_HOME, $runtime.TEMP, $javaHome | Out-Null
    $runtime.MAVEN_OPTS = (@($env:MAVEN_OPTS, ('-Djava.io.tmpdir="{0}"' -f $runtime.TEMP)) | Where-Object { $_ }) -join ' '
    # 先清除继承的业务配置，再注入本次调用明确指定的环境变量。
    foreach ($entry in Get-ChildItem Env:VF_*) { $runtime[$entry.Name] = $null }
    foreach ($name in $Environment.Keys) { $runtime[$name] = $Environment[$name] }

    # 只临时调整当前进程环境，Maven 退出后恢复调用前的值。
    $original = @{}
    foreach ($name in $runtime.Keys) {
        $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }

    Push-Location $projectRoot
    try {
        foreach ($name in $runtime.Keys) {
            [Environment]::SetEnvironmentVariable($name, $runtime[$name], 'Process')
        }
        & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress "-Dmaven.repo.local=$repository" @MavenArguments | Out-Host
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
        foreach ($name in $original.Keys) {
            [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process')
        }
    }

    return $exitCode
}

# 检查报告生成时间、执行数量及失败与跳过计数，避免旧报告掩盖本轮结果。
function Assert-ProjectTestReports {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [string]$Pattern = 'TEST-*.xml',
        [datetime]$NotBeforeUtc = [datetime]::MinValue
    )
    $reports = @(Get-ChildItem -LiteralPath $Directory -Filter $Pattern -File -ErrorAction Stop)
    if (!$reports.Count) { throw "No test reports in $Directory matching $Pattern" }
    $total = 0
    foreach ($report in $reports) {
        if ($report.LastWriteTimeUtc -lt $NotBeforeUtc) { throw "Stale test report: $($report.Name)" }
        [xml]$result = Get-Content -LiteralPath $report.FullName -Raw
        $suite = $result.testsuite
        if (!$suite -or [int]$suite.tests -eq 0 -or
                [int]$suite.failures + [int]$suite.errors + [int]$suite.skipped -ne 0) {
            throw "Incomplete or failed tests: $($report.Name)"
        }
        $total += [int]$suite.tests
    }
    Write-Host "Verified $total tests: $Directory"
    return $total
}
