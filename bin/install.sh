#!/usr/bin/env bash
# 职责：在 Linux/macOS 上把 arch-view 命令安装到用户目录，指向本工具目录，不改变目标项目或系统运行配置。
set -euo pipefail

destination="${1:-$HOME/.local/bin}"
arch_view_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

for required_file in src/arch_view/cli.clj ARCHITECTURE_TEMPLATE.md .env.example target/test-runtime/clojure.jar; do
  if [ ! -f "$arch_view_root/$required_file" ]; then
    echo "工具目录缺少文件：$required_file；请先准备完整的工具文件和运行依赖。" >&2
    exit 1
  fi
done

config_path="$arch_view_root/.env"
if [ ! -e "$config_path" ]; then
  cp "$arch_view_root/.env.example" "$config_path"
  # 多数 Linux 发行版只有 python3，没有 python 命令。
  if ! command -v python >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    printf '\nARCH_VIEW_PYTHON=python3\n' >> "$config_path"
  fi
fi

mkdir -p "$destination"
target="$destination/arch-view"
if [ -f "$target" ] && [ ! -e "$target.bak" ]; then
  cp "$target" "$target.bak"
fi

{
  echo '#!/usr/bin/env bash'
  echo '# 职责：从任意项目目录启动已安装的 arch-view 统一命令入口，并原样传递命令参数。'
  printf 'arch_view_home=%q\n' "$arch_view_root"
  cat <<'EOF'
if ! command -v java >/dev/null 2>&1; then
  echo '未找到 Java 运行环境；分析 Java 项目需要完整的开发环境（JDK）。' >&2
  exit 1
fi
ui_scale="${ARCH_VIEW_UI_SCALE:-1.25}"
classpath="$arch_view_home/classes:$arch_view_home/target/processing-core-4.4.1.jar:$arch_view_home/target/test-runtime/*:$arch_view_home/src"
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Darch-view.home=$arch_view_home" "-Dsun.java2d.uiScale=$ui_scale" -cp "$classpath" clojure.main -m arch-view.cli "$@"
EOF
} > "$target"
chmod +x "$target"

echo "已安装命令入口：$target"
echo "工具目录：$arch_view_root"
echo "全局配置：$config_path"
case ":$PATH:" in
  *":$destination:"*) ;;
  *) echo "请将 $destination 加入 PATH 后再使用 arch-view，例如在 ~/.bashrc 中添加：export PATH=\"$destination:\$PATH\"" ;;
esac
echo '运行 arch-view --help 查看命令，或运行 arch-view serve . 打开当前项目的网页架构。'
