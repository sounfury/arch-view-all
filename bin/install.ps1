# 职责：把 arch-view 命令入口安装到用户目录，指向本工具目录，不改变目标项目或系统运行配置。
[CmdletBinding()]
param(
    [string]$Destination = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.local\bin')
)

$ErrorActionPreference = 'Stop'
$archViewRoot = Split-Path -Parent $PSScriptRoot
$archViewLauncher = Join-Path $PSScriptRoot 'arch-view.ps1'
foreach ($requiredFile in @('src\arch_view\cli.clj', '.env.example', 'target\test-runtime\clojure.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $archViewRoot $requiredFile) -PathType Leaf)) {
        throw "工具目录缺少文件：$requiredFile；请先准备完整的工具文件和运行依赖。"
    }
}

$configPath = Join-Path $archViewRoot '.env'
if (-not (Test-Path -LiteralPath $configPath)) {
    Copy-Item -LiteralPath (Join-Path $archViewRoot '.env.example') -Destination $configPath
}

$Destination = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$launcherPath = $archViewLauncher.Replace("'", "''")
$powershellShim = @'
# 职责：从任意项目目录调用已安装的架构工具，并原样传递命令参数。
[string[]]$ScriptArgs = @($args)
$archViewLauncher = '__LAUNCHER__'
& $archViewLauncher @ScriptArgs
exit $LASTEXITCODE
'@
$powershellShim = $powershellShim.Replace('__LAUNCHER__', $launcherPath)
$powershellShim = $powershellShim.Replace("`r`n", "`n").Replace("`n", "`r`n")
$commandShim = @'
@echo off
rem 职责：从命令提示符调用同目录的架构工具入口。
powershell.exe -NoProfile -File "%~dp0arch-view.ps1" %*
exit /b %errorlevel%
'@
$commandShim = $commandShim.Replace("`r`n", "`n").Replace("`n", "`r`n")
$shellShim = @'
#!/usr/bin/env sh
# 职责：从 Git Bash 等 POSIX shell 调用已安装的架构工具，并原样传递命令参数。
exec powershell.exe -NoProfile -File '__LAUNCHER__' "$@"
'@
$shellShim = $shellShim.Replace('__LAUNCHER__', $archViewLauncher.Replace("'", "'''")).Replace("`r`n", "`n")

foreach ($name in @('arch-view.ps1', 'arch-view.cmd', 'arch-view')) {
    $target = Join-Path $Destination $name
    if ((Test-Path -LiteralPath $target -PathType Leaf) -and -not (Test-Path -LiteralPath ($target + '.bak'))) {
        Copy-Item -LiteralPath $target -Destination ($target + '.bak')
    }
}
[System.IO.File]::WriteAllText((Join-Path $Destination 'arch-view.ps1'), $powershellShim, [System.Text.UTF8Encoding]::new($true))
[System.IO.File]::WriteAllText((Join-Path $Destination 'arch-view.cmd'), $commandShim, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $Destination 'arch-view'), $shellShim, [System.Text.UTF8Encoding]::new($false))
Write-Output "已安装命令入口：$Destination"
Write-Output "工具目录：$archViewRoot"
Write-Output "全局配置：$configPath"
if (-not (($env:Path -split ';').TrimEnd('\') -contains $Destination.TrimEnd('\'))) {
    Write-Output "请将 $Destination 加入你的用户 PATH 后再使用 arch-view。"
}
Write-Output '运行 arch-view --help 查看命令，或运行 arch-view serve . 打开当前项目的网页架构。'
