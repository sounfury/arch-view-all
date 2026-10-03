@echo off
rem 职责：从命令提示符找到工具目录，并启动统一的 arch-view 命令入口。
setlocal

if not defined ARCH_VIEW_HOME set "ARCH_VIEW_HOME=%~dp0.."

if not defined ARCH_VIEW_UI_SCALE (
    set "ARCH_VIEW_UI_SCALE=1.25"
)

set "ARCH_VIEW_CLASSPATH=%ARCH_VIEW_HOME%\classes;%ARCH_VIEW_HOME%\target\processing-core-4.4.1.jar;%ARCH_VIEW_HOME%\target\test-runtime\*;%ARCH_VIEW_HOME%\src"

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Darch-view.home=%ARCH_VIEW_HOME%" -Dsun.java2d.uiScale=%ARCH_VIEW_UI_SCALE% -cp "%ARCH_VIEW_CLASSPATH%" clojure.main -m arch-view.cli %*

exit /b %errorlevel%
