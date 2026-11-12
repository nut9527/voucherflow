# 构造项目内 Compose 调用参数，统一环境文件、配置路径和项目名称。
function Get-ProjectComposeArguments {
    param(
        [string[]]$ComposeFiles = @('compose.yaml'),
        [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')][string]$Project = 'voucherflow-local'
    )
    $root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
    $arguments = @('compose', '--project-directory', $root, '--env-file', (Join-Path $root '.env'), '-p', $Project)
    foreach ($file in $ComposeFiles) {
        $path = [IO.Path]::GetFullPath($file, $root)
        if (!$path.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
                !(Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'Compose files must exist inside this project.'
        }
        $arguments += @('-f', $path)
    }
    return $arguments
}
