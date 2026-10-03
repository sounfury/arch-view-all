# 职责：找到工具安装目录和运行依赖，将用户参数交给统一的 arch-view 命令入口。
# 直接使用原始参数，避免通用参数机制拦截 -out 等命令行选项。
[string[]]$ScriptArgs = @($args)

$archViewHome = if ($env:ARCH_VIEW_HOME) { $env:ARCH_VIEW_HOME } else { Split-Path -Parent $PSScriptRoot }
$archViewHome = [System.IO.Path]::GetFullPath($archViewHome)
$uiScale = if ($env:ARCH_VIEW_UI_SCALE) { $env:ARCH_VIEW_UI_SCALE } else { "1.25" }
$classPath = "$archViewHome\classes;$archViewHome\target\processing-core-4.4.1.jar;$archViewHome\target\test-runtime\*;$archViewHome\src"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error '未找到 Java 运行环境；分析 Java 项目需要完整的开发环境（JDK）。'
    exit 1
}
$archViewConsoleEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' "-Darch-view.home=$archViewHome" "-Dsun.java2d.uiScale=$uiScale" -cp "$classPath" clojure.main -m arch-view.cli @ScriptArgs
    $archViewExitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $archViewConsoleEncoding
}
exit $archViewExitCode
