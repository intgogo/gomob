#!/bin/bash
# scripts/check_doc_index.sh — 索引文档防臃肿守门
#
# 检查 docs/agent-memory/AGENTS_MEMORY.md 与 docs/architecture.md 的索引条目行长。
# 规则: 索引是导航不是摘要, 描述 ≤60 字。剥离链接 URL 后整行 (标题+描述+指针文字)
# 上限 200 字符; 超限 = 把细节塞进了索引行 → 迁移进记忆文件/章节文档, 索引只留钩子。
#
# 用法: ./scripts/check_doc_index.sh    # 退码 0=通过 / 1=有超长行
set -e
cd "$(cd "$(dirname "$0")/.." && pwd)"

python3 - <<'EOF'
import re, sys, os
LIMIT = 200
fail = 0
for path in ('docs/agent-memory/AGENTS_MEMORY.md', 'docs/architecture.md'):
    if not os.path.exists(path):
        continue
    for i, line in enumerate(open(path, encoding='utf-8'), 1):
        line = line.rstrip('\n')
        s = line.lstrip()
        if not (s.startswith('- ') and '](' in s):
            continue
        text = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', line)  # 链接只留文字
        if len(text) > LIMIT:
            print(f'{path}:{i} 超长 (剥链接后 {len(text)} 字符 > {LIMIT}): {text[:80]}…')
            fail += 1
if fail:
    print(f'\n共 {fail} 条超长索引行。细节请迁入对应记忆文件/章节文档, 索引行只留 ≤60 字导航钩子。')
    sys.exit(1)
print('索引行长检查通过')
EOF
