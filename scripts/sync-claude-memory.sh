#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

encode_project_path() {
  local raw="$1"
  raw="${raw// /-}"
  raw="${raw//\//-}"
  printf "%s\n" "$raw"
}

project_key="$(encode_project_path "$repo_root")"
source_dir=".claude/projects/${project_key}/memory"
target_dir="docs/agent-memory/claude-sync"
index_file="${target_dir}/README.md"

if [[ ! -d "$source_dir" ]]; then
  printf "未找到 Claude memory 目录: %s\n" "$source_dir" >&2
  exit 1
fi

mkdir -p "$target_dir"

find "$target_dir" -maxdepth 1 -type f ! -name 'README.md' -delete

source_files_tmp="$(mktemp)"
find "$source_dir" -maxdepth 1 -type f | sort > "$source_files_tmp"

{
  printf "# Claude Sync\n\n"
  printf "本目录由 \`bash scripts/sync-claude-memory.sh\` 自动同步生成。\n\n"
  printf -- "- 来源目录：\`%s\`\n" "$source_dir"
  printf -- "- 同步策略：镜像 Claude 私有 memory 的文件名与内容，不改写手工维护的 \`docs/agent-memory/\` 根目录文件\n"
  printf -- "- 注意：这里是同步镜像层，稳定且已确认的长期规则，仍建议再人工整理到 \`docs/agent-memory/\` 根目录\n\n"
  printf "## 已同步文件\n\n"

  if [[ ! -s "$source_files_tmp" ]]; then
    printf -- "- 当前没有可同步的 memory 文件\n"
  fi
} > "$index_file"

while IFS= read -r src; do
  base="$(basename "$src")"
  cp "$src" "${target_dir}/${base}"
  printf -- "- [%s](%s)\n" "$base" "$base" >> "$index_file"
done < "$source_files_tmp"

rm -f "$source_files_tmp"

printf "Claude memory 已同步到 %s\n" "$target_dir"
