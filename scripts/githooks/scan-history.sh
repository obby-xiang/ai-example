#!/bin/sh
# =====================================================================
# 全历史敏感信息扫描（DC-08 复查用）
#
#   范围：git rev-list --objects --all 可达的全部唯一 blob（含历史版本中被
#         改掉/删掉的内容），不只扫工作树，也不只扫 HEAD。
#   规则：与 pre-commit 完全一致（共用 scripts/githooks/scan-lib.sh）：
#         SECRET / PATH / USER
#   退出码：0 = 无命中；1 = 有命中；2 = 环境/参数错误
#
#   用法：
#     scripts/githooks/scan-history.sh                 # 全量扫描并列出命中
#     scripts/githooks/scan-history.sh --max-size 512   # 跳过 >512KB 的 blob
#     scripts/githooks/scan-history.sh --files-only    # 只列命中文件与条数
#
#   说明：命中行的行号对应"该历史版本的 blob 内容"，与当前工作树行号可能不同；
#         每条命中附带 blob 短 sha 便于 git cat-file 定位。
# =====================================================================
set -u

max_size_kb=2048
files_only=0

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0 ;;
    --max-size)
      shift
      [ $# -gt 0 ] || { echo "✖ --max-size 需要参数（单位 KB）" >&2; exit 2; }
      max_size_kb="$1" ;;
    --files-only)
      files_only=1 ;;
    *)
      echo "✖ 未知参数: $1（--help 查看用法）" >&2
      exit 2 ;;
  esac
  shift
done

case "$max_size_kb" in
  ''|*[!0-9]*) echo "✖ --max-size 必须是非负整数（单位 KB）" >&2; exit 2 ;;
esac

hook_dir=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd) || hook_dir=""
if [ -z "$hook_dir" ] || [ ! -f "$hook_dir/scan-lib.sh" ]; then
  top=$(git rev-parse --show-toplevel 2>/dev/null || true)
  if [ -n "$top" ] && [ -f "$top/scripts/githooks/scan-lib.sh" ]; then
    hook_dir="$top/scripts/githooks"
  fi
fi
if [ ! -f "$hook_dir/scan-lib.sh" ]; then
  echo "✖ 未找到扫描引擎 scan-lib.sh（$hook_dir）" >&2
  exit 2
fi
. "$hook_dir/scan-lib.sh"

TAB=$(printf '\t')
TMP_MAP=$(mktemp) || exit 2
TMP_TYPES=$(mktemp) || exit 2
TMP_LIST=$(mktemp) || exit 2
TMP_HITS=$(mktemp) || exit 2
trap 'rm -f "$TMP_MAP" "$TMP_TYPES" "$TMP_LIST" "$TMP_HITS" "$TMP_HITS.sorted"' EXIT HUP INT TERM

scan_init
scan_user_note

# 1) 枚举全历史对象，建立 blob -> 首个可见路径 的映射
git rev-list --objects --all \
  | LC_ALL=C awk '
      /^[0-9a-f]{40} / {
        sha = substr($0, 1, 40)
        if (!(sha in path)) path[sha] = substr($0, 42)
      }
      END { for (s in path) printf "%s\t%s\n", s, path[s] }
    ' > "$TMP_MAP" || { echo "✖ git rev-list --objects --all 失败" >&2; exit 2; }

# 2) 只保留 blob 类型、且体积不超过阈值的对象
#    TMP_TYPES 为空格分隔（sha type size），TMP_MAP 为 TAB 分隔，故此处按行显式切分
LC_ALL=C awk -F"$TAB" '{ print $1 }' "$TMP_MAP" \
  | git cat-file --batch-check='%(objectname) %(objecttype) %(objectsize)' > "$TMP_TYPES" 2>/dev/null

LC_ALL=C awk -v max="$max_size_kb" '
  NR == FNR { t[$1] = $2; z[$1] = $3; next }
  { n = split($0, a, "\t") }
  n >= 2 && (a[1] in t) && t[a[1]] == "blob" && z[a[1]] + 0 <= max * 1024 { printf "%s\t%s\n", a[1], a[2] }
' "$TMP_TYPES" "$TMP_MAP" > "$TMP_LIST"

total=$(LC_ALL=C wc -l < "$TMP_LIST" | tr -d ' ')
if [ "$total" = "0" ]; then
  echo "✖ 未取到任何 blob（仓库为空或 rev-list 异常）" >&2
  exit 2
fi
echo "扫描范围: $total 个唯一 blob（git rev-list --objects --all，串行逐个读取）" >&2
[ "$max_size_kb" != "0" ] && echo "  体积上限: ${max_size_kb}KB（超过则跳过）" >&2

# 3) 逐个 blob 扫描
#    二进制/图片类 blob 先跳过：它们的字节流会让路径规则产生无意义命中
n=0
skip_binary=0
while IFS="$TAB" read -r sha path; do
  [ -z "$sha" ] && continue
  n=$((n + 1))
  if [ $((n % 200)) -eq 0 ]; then
    printf '  ... 已扫描 %d/%d\n' "$n" "$total" >&2
  fi
  case "$path" in
    *.png|*.jpg|*.jpeg|*.gif|*.ico|*.bmp|*.webp|*.pdf|*.zip|*.gz|*.jar|*.class|\
    *.woff|*.woff2|*.ttf|*.eot|*.mp4|*.mov|*.mp3|*.sqlite|*.db|*.exe|*.dll|*.so|\
    *.dylib|*.bin|*.xlsx|*.xls|*.docx|*.doc|*.pptx)
      skip_binary=$((skip_binary + 1)); continue ;;
    *.md|*.txt|*.log|*.json|*.jsonl|*.yml|*.yaml|*.xml|*.html|*.htm|*.css|*.scss|\
    *.less|*.js|*.jsx|*.mjs|*.cjs|*.ts|*.tsx|*.vue|*.java|*.kt|*.py|*.sh|*.bash|\
    *.bat|*.ps1|*.sql|*.conf|*.cfg|*.ini|*.properties|*.gradle|*.lock|*.toml|\
    *.csv|*.tsv|*.env|*.example|*.in|*.mod|*.sum|*.map|*.sarif|*.patch|*.diff|\
    *.iml|*.svg|Makefile|*/Makefile|Dockerfile|*/Dockerfile)
      ;;
    *)
      if command -v od >/dev/null 2>&1 \
         && git cat-file blob "$sha" 2>/dev/null | LC_ALL=C od -An -c -N 2048 | LC_ALL=C grep -q '\\0'; then
        skip_binary=$((skip_binary + 1)); continue
      fi ;;
  esac
  hits=$(git cat-file blob "$sha" 2>/dev/null \
           | scan_engine_blob "$path" \
           | LC_ALL=C sort -u)
  [ -z "$hits" ] && continue
  printf '%s\n' "$hits" \
    | LC_ALL=C awk -F"$TAB" -v b="$sha" '{ printf "%s\t%s\t%s\t%s\t%s\n", $1, $2, $3, $4, substr(b, 1, 8) }' \
    >> "$TMP_HITS"
done < "$TMP_LIST"

echo "" >&2
hit_count=$(LC_ALL=C wc -l < "$TMP_HITS" | tr -d ' ')
file_count=$(LC_ALL=C awk -F"$TAB" '{ print $1 }' "$TMP_HITS" | LC_ALL=C sort -u | LC_ALL=C wc -l | tr -d ' ')

echo "================ 全历史敏感信息扫描结果 ================"
echo "命中 $hit_count 处，涉及 $file_count 个历史路径（blob 粒度，按内容去重）"
[ "$skip_binary" != "0" ] && echo "已跳过 $skip_binary 个二进制/图片 blob（不参与匹配）"
echo ""
echo "-- 命中路径清单（仅路径与条数，不含内容） --"
LC_ALL=C awk -F"$TAB" '{ print $1 }' "$TMP_HITS" | LC_ALL=C sort | LC_ALL=C uniq -c | LC_ALL=C sort -rn
if [ "$files_only" = 0 ]; then
  echo ""
  echo "-- 命中明细（SECRET 片段已掩码；行号对应历史版本内容） --"
  LC_ALL=C awk -F"$TAB" '{ printf "%s\t%s\t%s\t%s\t%s\n", $1, $2, $3, $4, $5 }' "$TMP_HITS" \
    | LC_ALL=C sort -u > "$TMP_HITS.sorted"
  while IFS="$TAB" read -r f l r t b; do
    [ -z "$r" ] && continue
    case "$r" in
      SECRET) label="SECRET 疑似密钥/令牌" ;;
      PATH)   label="PATH   本机绝对路径" ;;
      USER)   label="USER   个人用户名" ;;
      *)      label="$r" ;;
    esac
    printf '  %s:%s  [%s]  %s  (blob %s)\n' "$f" "$l" "$label" "$t" "$b"
  done < "$TMP_HITS.sorted"
  rm -f "$TMP_HITS.sorted"
fi

if [ "$hit_count" != "0" ]; then
  echo ""
  echo "⚠ 存在历史命中：这些内容已进入 git 历史，删除工作树文件不会消除，"
  echo "  需按 DC-08 决定是否改写历史（filter-repo/BFG）并轮换已泄漏的凭据。"
  exit 1
fi

echo "✓ 全历史扫描通过：无命中"
exit 0
