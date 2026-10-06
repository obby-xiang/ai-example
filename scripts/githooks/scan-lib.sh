#!/bin/sh
# =====================================================================
# 敏感信息扫描引擎（共享库，被 pre-commit 与 scan-history.sh source）
#
# 自指规避（重要）：本文件内部所有规则前缀/字面量一律拆开拼接（"s""k-" 等），
#   使本文件自身的文本不会命中本文件的规则，保证"扫描器不自报"。
#
# 对外接口：
#   scan_init                初始化规则正则（读 git config user.name）；设置 SCAN_RE_* / SCAN_USER_NOTE
#   scan_engine_stream       stdin: "路径<TAB>行号<TAB>正文"      -> stdout: 命中行
#   scan_engine_blob PATH    stdin: 裸文件内容（行号自 NR 起）     -> stdout: 命中行
#   scan_format              stdin: 命中行 -> 人类可读 "路径:行号 [规则] 片段"
#   命中行格式：路径<TAB>行号<TAB>规则<TAB>片段（SECRET 片段已掩码）
#   规则名：SECRET / PATH / USER
# =====================================================================

SCAN_TAB=$(printf '\t')

# ---------- 字符类 ----------
SCAN_A="A-Za-z0-9"
SCAN_W="A-Za-z0-9_"
SCAN_T="A-Za-z0-9_-"
SCAN_H="0-9A-Z"

# ---------- 密钥前缀（拼接构造，禁止合并成完整字面量） ----------
SCAN_P_SK="s""k-"
SCAN_P_GHP="gh""p_"
SCAN_P_GHO="gh""o_"
SCAN_P_GHS="gh""s_"
SCAN_P_GHU="gh""u_"
SCAN_P_GHPAT="gith""ub_pat_"
SCAN_P_AKIA="AK""IA"
SCAN_P_ASIA="AS""IA"
SCAN_P_AIZA="AI""za"
SCAN_P_XOX="xo""x"
SCAN_P_HF="h""f_"
SCAN_P_LTAI="LT""AI"
SCAN_P_AKID="AK""ID"
SCAN_P_GLPAT="glp""at-"
SCAN_P_NPM="np""m_"
SCAN_P_SKLIVE="s""k_live_"
SCAN_P_RKLIVE="r""k_live_"
SCAN_P_BEARER1="[Bb]"
SCAN_P_BEARER2="ea""rer"
SCAN_P_JWT="ey""J"
SCAN_P_PK1="-----BEGIN"
SCAN_P_PK2="PRIVATE KEY-----"

# ---------- git config user.name 的通用值（命中即跳过 USER 规则，避免误伤） ----------
SCAN_GENERIC_NAMES="root admin administrator user test tester ci cd git runner nobody docker jenkins ubuntu debian windows dev developer localhost"

scan_join() {
  _scan_out=""
  for _scan_p in "$@"; do
    [ -z "$_scan_p" ] && continue
    if [ -z "$_scan_out" ]; then _scan_out="$_scan_p"; else _scan_out="$_scan_out|$_scan_p"; fi
  done
  printf '%s' "$_scan_out"
}

scan_re_escape() {
  printf '%s' "$1" | sed -e 's/[][\^$.*+?(){}|]/\\&/g'
}

scan_rule_secret() {
  scan_join \
    "(^|[^$SCAN_A])${SCAN_P_SK}[$SCAN_T]{20,}" \
    "${SCAN_P_GHP}[$SCAN_A]{30,}" \
    "${SCAN_P_GHO}[$SCAN_A]{30,}" \
    "${SCAN_P_GHS}[$SCAN_A]{30,}" \
    "${SCAN_P_GHU}[$SCAN_A]{30,}" \
    "${SCAN_P_GHPAT}[$SCAN_T]{20,}" \
    "${SCAN_P_AKIA}[$SCAN_H]{16}" \
    "${SCAN_P_ASIA}[$SCAN_H]{16}" \
    "${SCAN_P_AIZA}[$SCAN_T]{30,}" \
    "${SCAN_P_XOX}[baprs]-[$SCAN_T]{10,}" \
    "${SCAN_P_HF}[$SCAN_A]{30,}" \
    "${SCAN_P_LTAI}[$SCAN_A]{12,}" \
    "${SCAN_P_AKID}[$SCAN_A]{13,}" \
    "${SCAN_P_GLPAT}[$SCAN_T]{20,}" \
    "${SCAN_P_NPM}[$SCAN_A]{36}" \
    "${SCAN_P_SKLIVE}[$SCAN_A]{20,}" \
    "${SCAN_P_RKLIVE}[$SCAN_A]{20,}" \
    "(^|[^$SCAN_A])${SCAN_P_BEARER1}${SCAN_P_BEARER2}[[:space:]]+[-${SCAN_A}_.~+/=]{20,}" \
    "${SCAN_P_JWT}[$SCAN_T]{10,}\.[$SCAN_T]{10,}\.[$SCAN_T]{5,}" \
    "${SCAN_P_PK1}[A-Z ]*${SCAN_P_PK2}"
}

scan_rule_path() {
  scan_join \
    "(^|[^$SCAN_W])[A-Za-z]:[\\\\/][-${SCAN_A}_.\\\\/]{1,60}" \
    "(^|[^$SCAN_W/])/[cdefgh]/[-${SCAN_A}_.\\\\/]{1,60}"
}

scan_init() {
  SCAN_RE_SECRET=$(scan_rule_secret)
  SCAN_RE_PATH=$(scan_rule_path)
  SCAN_RE_USER=""
  SCAN_USER_NOTE=""
  SCAN_USER_NAME=$(git config --get user.name 2>/dev/null || true)

  if [ -z "$SCAN_USER_NAME" ]; then
    SCAN_USER_NOTE="USER 规则已跳过：git config user.name 未设置"
    export SCAN_RE_SECRET SCAN_RE_PATH SCAN_RE_USER
    return 0
  fi

  _low=$(printf '%s' "$SCAN_USER_NAME" | tr 'A-Z' 'a-z')
  for _g in $SCAN_GENERIC_NAMES; do
    if [ "$_low" = "$_g" ]; then
      SCAN_USER_NOTE="USER 规则已跳过：user.name 为通用值（$_low）"
      export SCAN_RE_SECRET SCAN_RE_PATH SCAN_RE_USER
      return 0
    fi
  done

  _alt=""
  _terms=" "
  while IFS= read -r _part; do
    [ -z "$_part" ] && continue
    [ ${#_part} -lt 4 ] && continue
    case "$_terms" in
      *" $_part "*) continue ;;
    esac
    _terms="$_terms$_part "
    _alt=$(scan_join "$_alt" "$(scan_re_escape "$_part")")
  done <<EOF
$_low
$(printf '%s\n' "$_low" | sed -e 's/[._@[:space:]-]/\n/g')
EOF

  SCAN_RE_USER="$_alt"
  if [ -z "$SCAN_RE_USER" ]; then
    SCAN_USER_NOTE="USER 规则已跳过：user.name 拆解后无长度 >=4 的有效片段"
  fi
  export SCAN_RE_SECRET SCAN_RE_PATH SCAN_RE_USER
  return 0
}

# ---------- 暂存区 diff -> "路径<TAB>行号<TAB>正文" 的抽取器 ----------
SCAN_DIFF_AWK='
/^\+\+\+ / { f = substr($0, 7); sub(/^b\//, "", f); sub(/\t$/, "", f); next }
/^@@ /    { s = $0; sub(/^@@ [^+]*\+/, "", s); sub(/[, ].*$/, "", s); n = s + 0; next }
/^\+/     { print f "\t" n "\t" substr($0, 2); n++; next }
'

# ---------- 判定与输出 ----------
SCAN_AWK_BODY='
BEGIN {
  FS = "\t"
  mode = ENVIRON["SCAN_MODE"]
  cur  = ENVIRON["SCAN_CUR_PATH"]
  re_secret = ENVIRON["SCAN_RE_SECRET"]
  re_path   = ENVIRON["SCAN_RE_PATH"]
  re_user   = ENVIRON["SCAN_RE_USER"]
}
function exempt(t, pol, cb, ca,   l) {
  l = tolower(t)
  if (pol == 1) return (l ~ /(xxx|placeholder|redacted|[<>])/)
  if (pol == 2 && (cb ~ /[A-Za-z0-9]/ || ca ~ /[A-Za-z0-9]/)) return 1
  return (l ~ /(xxx|your|placeholder|redacted|example|dummy|sample|fake|change-?me|todo|\.\.\.)/)
}
function report(rule, tok) {
  if (rule == "SECRET") tok = substr(tok, 1, 4) "***" "(len=" length(tok) ")"
  printf "%s\t%s\t%s\t%s\n", f, ln, rule, tok
}
function scan_all(s, re, rule, pol,   tok, rest, adv, cb, ca) {
  if (re == "") return
  rest = s
  while (length(rest) > 0 && match(rest, re) > 0) {
    tok = substr(rest, RSTART, RLENGTH)
    cb = (RSTART > 1) ? substr(rest, RSTART - 1, 1) : ""
    ca = substr(rest, RSTART + RLENGTH, 1)
    if (!exempt(tok, pol, cb, ca)) report(rule, tok)
    adv = RSTART + RLENGTH
    if (adv < 1) adv = 1
    rest = substr(rest, adv)
  }
}
{
  if (mode == "blob") { f = cur; ln = NR; text = $0 }
  else {
    if ($0 !~ /\t/) next
    f = $1; ln = $2 + 0; text = substr($0, length($1) + length($2) + 3)
  }
  if (length(text) > 4000) next
  gsub(/<[^>]{1,80}>/, "", text)
  scan_all(text, re_secret, "SECRET", 0)
  scan_all(text, re_path,   "PATH",   1)
  if (re_user != "") scan_all(tolower(text), re_user, "USER", 2)
}
'

scan_engine_stream() {
  LC_ALL=C awk "$SCAN_AWK_BODY"
}

scan_engine_blob() {
  SCAN_MODE=blob SCAN_CUR_PATH="$1" LC_ALL=C awk "$SCAN_AWK_BODY"
}

scan_format() {
  while IFS="$SCAN_TAB" read -r _f _l _r _t; do
    [ -z "$_r" ] && continue
    case "$_r" in
      SECRET) _label="SECRET 疑似密钥/令牌" ;;
      PATH)   _label="PATH   本机绝对路径" ;;
      USER)   _label="USER   个人用户名" ;;
      *)      _label="$_r" ;;
    esac
    printf '  %s:%s  [%s]  %s\n' "$_f" "$_l" "$_label" "$_t"
  done
}

scan_legend() {
  echo "  规则: [SECRET 疑似密钥/令牌] [PATH 本机绝对路径] [USER 个人用户名]"
}

scan_user_note() {
  [ -n "${SCAN_USER_NOTE:-}" ] && echo "  注: $SCAN_USER_NOTE" >&2
  return 0
}
