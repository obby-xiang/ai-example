#!/usr/bin/env bash
# 三实例集群验证网（A/B/C · 共享 Redis + H2-TCP + 文件目录 + 统一反向代理）
#
# 设计口径：
#   - **真实集群只在 GitHub Actions 的 ubuntu-latest 上跑**；本机只允许 `--self-check`
#     （bash -n / node --check / 桩自证 / 探针自证 / 代理配置静态断言 / 工作流静态断言），
#     不启动 Java、不启动容器、不连任何端口。
#   - 端口段 231xx：Redis 23179、HAProxy 23180、实例 A/B/C 23181/23182/23183、
#     HAProxy stats 23189、H2 TCP 23190、桩上游 23199。与产品默认端口/前端端口无交集。
#   - 共享面：H2 TCP 单进程（三 JVM 通过 TCP 连同一个库）+ 同一个 Redis 容器 +
#     同一个共享文件目录（`app.file.storage-path`）。
#   - 反向代理：HAProxy 容器（host 网络），按**请求**轮询，配置里没有任何会话粘滞；
#     readiness 用 `GET /health/ready` 的 HTTP 200 判定（硬依赖 DOWN 或 draining 时 503；
#     Redis 是降级维度，不摘流）。AI key 只决定 capability，不影响实例 ready。
#   - 零密钥：桩上游 + 固定占位 key（后端只判 key 非空，不校验真假）；不读任何 GitHub secret。
#   - 不复用旧证据：容器名、端口段、产物目录（backend/target/cluster3-artifacts）全部独立；
#     不读取 b23l1 / redis5 等历史产物。
#
# 用法：
#   bash scripts/ci/cluster3-run.sh --self-check          # 静态/协议自证（本机可跑）
#   bash scripts/ci/cluster3-run.sh                       # 真实三实例（CI only）
#   bash scripts/ci/cluster3-run.sh --print-haproxy-config
#
# 退出码：0 = 全绿；1 = 有用例 FAIL；2 = 环境/参数错误（基线不可用）。
#
# 环境变量（可选）：
#   CLUSTER3_JAR / CLUSTER3_ARTIFACT_DIR / CLUSTER3_RUN_DIR / CLUSTER3_KEEP=1
#   REDIS_IMAGE（默认 redis:7.2.16，最后一个 BSD-3-Clause 线）/ HAPROXY_IMAGE（默认 haproxy:2.9-alpine）

set -u -o pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
SELF="$SCRIPT_DIR/cluster3-run.sh"
PROBE="$SCRIPT_DIR/cluster3-probe.mjs"
STUB="$SCRIPT_DIR/stub-upstream.mjs"
WORKFLOW="$REPO_ROOT/.github/workflows/cluster-3node.yml"

PORT_REDIS=23179
PORT_PROXY=23180
PORT_A=23181
PORT_B=23182
PORT_C=23183
PORT_STATS=23189
PORT_H2=23190
PORT_STUB=23199

REDIS_IMAGE="${REDIS_IMAGE:-redis:7.2.16}"
HAPROXY_IMAGE="${HAPROXY_IMAGE:-haproxy:2.9-alpine}"

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
REDIS_NAME="cluster3-${RUN_ID}-redis"
HAPROXY_NAME="cluster3-${RUN_ID}-haproxy"

JAR="${CLUSTER3_JAR:-$REPO_ROOT/backend/target/config-mgr.jar}"
ARTIFACT_DIR="${CLUSTER3_ARTIFACT_DIR:-$REPO_ROOT/backend/target/cluster3-artifacts}"
RUN_DIR="${CLUSTER3_RUN_DIR:-${RUNNER_TEMP:-/tmp}/cluster3-run-${RUN_ID}}"
DB_BASE="$RUN_DIR/db"
SHARED_FILES="$RUN_DIR/files"
HAPROXY_CFG="$RUN_DIR/haproxy/haproxy.cfg"
H2_JAR="$RUN_DIR/h2.jar"
JAVA_BIN="${JAVA_BIN:-java}"

RUN_MODE=0
STUB_PID_FILE=""
CI_LOG="$ARTIFACT_DIR/ci.log"

# 禁用端口字面量（分段拼接，避免本文件被自己的卫生扫描误命中）。
BANNED_PORT_1="80""81"
BANNED_PORT_2="52""02"

usage() {
	cat <<'EOF'
用法: bash scripts/ci/cluster3-run.sh [选项]

  --self-check              静态/协议自证（不启动 Java/Docker、不连端口）
  --print-haproxy-config    打印实际使用的 HAProxy 配置（供自检/人工复核）
  (无参数)                  启动真实三实例集群并跑全部用例（仅 CI）
  -h, --help                显示帮助
EOF
}

# ── HAProxy 配置（单一事实源：自检与实际运行读同一份）──────────────────────

print_haproxy_config() {
	cat <<EOF
# 三实例集群反向代理（由 scripts/ci/cluster3-run.sh 生成；无会话粘滞）
global
    log stdout format raw local0
    maxconn 4096

defaults
    mode http
    log global
    option http-keep-alive
    option forwardfor
    option redispatch
    retries 0
    timeout connect 3s
    timeout client 300s
    timeout server 310s
    timeout http-request 15s
    timeout http-keep-alive 30s
    timeout check 5s
    default-server inter 2s fastinter 500ms downinter 2s fall 2 rise 2
    log-format "%ci:%cp [%tr] %ft %b/%s %HM %HU %ST %Tt"

frontend fe_cluster3
    bind *:${PORT_PROXY}
    default_backend be_cluster3

backend be_cluster3
    balance roundrobin
    option httpchk GET /health/ready
    http-check expect status 200
    http-response set-header X-Cluster-Node %[srv_name]
    http-response set-header X-Cluster-Backend %[be_name]
    server node-a 127.0.0.1:${PORT_A} check
    server node-b 127.0.0.1:${PORT_B} check
    server node-c 127.0.0.1:${PORT_C} check

listen stats
    mode http
    bind *:${PORT_STATS}
    stats enable
    stats uri /stats
    stats refresh 2s
    stats show-legends
EOF
}

# ── 自检 ───────────────────────────────────────────────────────────────────

SC_PASS=0
SC_FAIL=0
SC_FAILURES=()

sc_ok() {
	SC_PASS=$((SC_PASS + 1))
	printf 'PASS  %s\n' "$1"
}

sc_no() {
	SC_FAIL=$((SC_FAIL + 1))
	SC_FAILURES+=("$1")
	printf 'FAIL  %s\n' "$1"
}

sc_run() {
	local name=$1
	shift
	if "$@" >/dev/null 2>&1; then sc_ok "$name"; else sc_no "$name"; fi
}

sc_assert() {
	local name=$1
	shift
	if "$@"; then sc_ok "$name"; else sc_no "$name"; fi
}

sc_contains() {
	local name=$1 text=$2 needle=$3
	if printf '%s' "$text" | grep -qF -- "$needle"; then sc_ok "$name"; else sc_no "$name（缺少：$needle）"; fi
}

sc_not_contains() {
	local name=$1 text=$2 needle=$3
	if printf '%s' "$text" | grep -qF -- "$needle"; then sc_no "$name（出现禁用项：$needle）"; else sc_ok "$name"; fi
}

file_hygiene() {
	local rel=$1
	local full="$REPO_ROOT/$rel"
	if [ ! -f "$full" ]; then
		sc_no "文件存在：$rel"
		return
	fi
	sc_ok "文件存在：$rel"
	if grep -q $'\r' "$full"; then sc_no "LF 行尾：$rel"; else sc_ok "LF 行尾：$rel"; fi
	if grep -qE '(^|[^A-Za-z0-9])[A-Za-z]:[\\/]' "$full"; then sc_no "无盘符绝对路径：$rel"; else sc_ok "无盘符绝对路径：$rel"; fi
	if grep -qE 'AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----' "$full"; then sc_no "无密钥字面量：$rel"; else sc_ok "无密钥字面量：$rel"; fi
	if grep -qF -- "$BANNED_PORT_1" "$full" || grep -qF -- "$BANNED_PORT_2" "$full"; then
		sc_no "无禁用端口：$rel"
	else
		sc_ok "无禁用端口：$rel"
	fi
	if grep -qE '\b231[0-9]{2}\b' "$full"; then sc_ok "端口段 231xx：$rel"; else sc_no "端口段 231xx：$rel"; fi
}

tree_clean() {
	local allowed="scripts/ci/cluster3-run.sh scripts/ci/cluster3-probe.mjs .github/workflows/cluster-3node.yml"
	local line rel
	local dirty
	dirty="$(cd "$REPO_ROOT" && git status --porcelain=v1 --untracked-files=all 2>/dev/null || true)"
	if [ -z "$dirty" ]; then
		sc_ok "工作树只含本批新增文件（当前无改动）"
		return
	fi
	while IFS= read -r line; do
		[ -z "$line" ] && continue
		rel="${line:3}"
		case " $allowed " in
		*" $rel "*) ;;
		*) sc_no "工作树含本批之外的文件：$rel"; return ;;
		esac
	done <<<"$dirty"
	sc_ok "工作树只含本批新增文件（未改产品代码/docs）"
}

self_check() {
	cd "$REPO_ROOT" || exit 2
	printf '三实例集群验证网 --self-check（不启动 Java/Docker、不连接任何端口）\n\n'

	sc_run "bash -n cluster3-run.sh" bash -n "$SELF"
	sc_run "node --check cluster3-probe.mjs" node --check "$PROBE"
	sc_run "node --check stub-upstream.mjs" node --check "$STUB"
	if node "$PROBE" --self-check; then sc_ok "cluster3-probe --self-check"; else sc_no "cluster3-probe --self-check"; fi
	if node "$STUB" --self-check >/dev/null 2>&1; then sc_ok "stub-upstream --self-check"; else sc_no "stub-upstream --self-check"; fi

	# 代理配置：无粘滞 + 轮询 + readiness + 端口
	local cfg
	cfg="$(print_haproxy_config)"
	sc_contains "代理：balance roundrobin" "$cfg" "balance roundrobin"
	sc_contains "代理：readiness 探针路径" "$cfg" "option httpchk GET /health/ready"
	sc_contains "代理：readiness 期望 200" "$cfg" "http-check expect status 200"
	sc_contains "代理：响应头回写节点" "$cfg" "http-response set-header X-Cluster-Node"
	sc_contains "代理：server node-a" "$cfg" "server node-a 127.0.0.1:${PORT_A} check"
	sc_contains "代理：server node-b" "$cfg" "server node-b 127.0.0.1:${PORT_B} check"
	sc_contains "代理：server node-c" "$cfg" "server node-c 127.0.0.1:${PORT_C} check"
	sc_contains "代理：stats 端口" "$cfg" "bind *:${PORT_STATS}"
	sc_not_contains "代理：无 stick-table" "$cfg" "stick-table"
	sc_not_contains "代理：无 cookie 粘滞" "$cfg" "cookie "
	sc_not_contains "代理：无 balance source" "$cfg" "balance source"
	sc_not_contains "代理：无 balance hdr" "$cfg" "balance hdr"
	sc_not_contains "代理：无禁用端口 1" "$cfg" "$BANNED_PORT_1"
	sc_not_contains "代理：无禁用端口 2" "$cfg" "$BANNED_PORT_2"

	# 工作流静态断言（不引入 YAML 解析器；只认仓库现有口径的硬事实）
	if [ -f "$WORKFLOW" ]; then
		sc_ok "工作流文件存在"
		if grep -q $'\t' "$WORKFLOW"; then sc_no "工作流：无 tab 缩进"; else sc_ok "工作流：无 tab 缩进"; fi
		sc_assert "工作流：手工触发（workflow_dispatch）" grep -q 'workflow_dispatch:' "$WORKFLOW"
		if grep -Eq '^  push:' "$WORKFLOW"; then sc_no "工作流：不挂 push"; else sc_ok "工作流：不挂 push"; fi
		if grep -Eq '^  pull_request:' "$WORKFLOW"; then sc_no "工作流：不挂 pull_request"; else sc_ok "工作流：不挂 pull_request"; fi
		sc_assert "工作流：ubuntu-latest" grep -q 'runs-on: ubuntu-latest' "$WORKFLOW"
		sc_assert "工作流：只读权限" grep -q 'contents: read' "$WORKFLOW"
		sc_assert "工作流：先跑 --self-check" grep -q 'cluster3-run.sh --self-check' "$WORKFLOW"
		sc_assert "工作流：再跑真实集群" grep -q 'bash scripts/ci/cluster3-run.sh$' "$WORKFLOW"
		sc_assert "工作流：打包 jar（跳过单测）" grep -q 'mvn -B -DskipTests package' "$WORKFLOW"
		sc_assert "工作流：失败也归档" grep -q 'upload-artifact@v5' "$WORKFLOW"
		sc_assert "工作流：归档保留期" grep -q 'retention-days:' "$WORKFLOW"
		sc_assert "工作流：整 job 超时" grep -q 'timeout-minutes:' "$WORKFLOW"
		if grep -q 'secrets\.' "$WORKFLOW"; then sc_no "工作流：不引用 secrets"; else sc_ok "工作流：不引用 secrets"; fi
		sc_assert "工作流：无并发冲突（同组串行）" grep -q 'cancel-in-progress: false' "$WORKFLOW"
	else
		sc_no "工作流文件存在：.github/workflows/cluster-3node.yml"
	fi

	file_hygiene "scripts/ci/cluster3-run.sh"
	file_hygiene "scripts/ci/cluster3-probe.mjs"
	file_hygiene ".github/workflows/cluster-3node.yml"
	tree_clean

	printf '\n自证结果：PASS %s / FAIL %s\n' "$SC_PASS" "$SC_FAIL"
	if [ "$SC_FAIL" -gt 0 ]; then
		printf '失败项：\n'
		local item
		for item in "${SC_FAILURES[@]}"; do printf -- '- %s\n' "$item"; done
		return 1
	fi
	printf 'SELF-CHECK PASS\n'
	return 0
}

# ── 运行期工具 ─────────────────────────────────────────────────────────────

log() {
	printf '[cluster3-run] %s\n' "$*"
}

port_in_use() {
	local port=$1
	if command -v ss >/dev/null 2>&1; then
		ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}$"
	else
		netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}$"
	fi
}

require_port_free() {
	local port=$1
	if port_in_use "$port"; then
		log "端口 $port 已被占用：拒绝复用未知服务（真实集群前置条件不成立）"
		exit 2
	fi
}

cleanup() {
	local rc=$?
	trap - EXIT INT TERM
	set +e
	if [ "$RUN_MODE" = "1" ]; then
		mkdir -p "$ARTIFACT_DIR/haproxy" "$ARTIFACT_DIR/redis"
		docker logs "$HAPROXY_NAME" >"$ARTIFACT_DIR/haproxy/haproxy.log" 2>&1 || true
		docker logs "$REDIS_NAME" >"$ARTIFACT_DIR/redis/redis.log" 2>&1 || true
		docker rm -f "$HAPROXY_NAME" "$REDIS_NAME" >/dev/null 2>&1 || true
		if [ -n "$STUB_PID_FILE" ] && [ -f "$STUB_PID_FILE" ]; then
			kill "$(cat "$STUB_PID_FILE")" >/dev/null 2>&1 || true
		fi
		local pidfile
		for pidfile in "$ARTIFACT_DIR"/instances/*.pid; do
			[ -f "$pidfile" ] || continue
			kill -9 "$(cat "$pidfile")" >/dev/null 2>&1 || true
		done
		if [ ! -f "$ARTIFACT_DIR/summary.md" ]; then
			{
				echo '# 三实例集群验证网'
				echo
				echo "- 结论：**FAIL（基础设施/执行器未完成）**"
				echo "- 退出码：$rc"
				echo "- 详见 ci.log / run.log"
			} >"$ARTIFACT_DIR/summary.md"
		fi
		if [ "${CLUSTER3_KEEP:-0}" != "1" ]; then
			rm -rf "$RUN_DIR"
		fi
	fi
	exit "$rc"
}

start_stub() {
	mkdir -p "$ARTIFACT_DIR/stub"
	STUB_PID_FILE="$ARTIFACT_DIR/stub.pid"
	log "启动桩上游：127.0.0.1:${PORT_STUB}（零密钥）"
	(
		cd "$REPO_ROOT" || exit 2
		# env -i：桩不需要 runner 环境；不把可能存在的密钥/凭据带进子进程。
		nohup env -i PATH="$PATH" HOME="${HOME:-/tmp}" LANG="${LANG:-C.UTF-8}" \
			node "$STUB" --port "$PORT_STUB" --dir "$ARTIFACT_DIR/stub" \
			>"$ARTIFACT_DIR/stub.log" 2>&1 &
		echo $! >"$STUB_PID_FILE"
	)
	local i
	for i in $(seq 1 60); do
		if curl -fsS -m 2 "http://127.0.0.1:${PORT_STUB}/probe" >/dev/null 2>&1; then
			log "桩上游就绪"
			return 0
		fi
		sleep 0.5
	done
	log "桩上游 30s 内未就绪"
	return 2
}

start_redis() {
	log "启动共享 Redis：${REDIS_NAME}（127.0.0.1:${PORT_REDIS}，${REDIS_IMAGE}）"
	docker run -d \
		--name "$REDIS_NAME" \
		--label "cluster3.run=${RUN_ID}" \
		-p "${PORT_REDIS}:6379" \
		"$REDIS_IMAGE" \
		--maxmemory-policy noeviction \
		--appendonly no >/dev/null || return 2
	local i
	for i in $(seq 1 60); do
		if [ "$(docker exec "$REDIS_NAME" redis-cli ping 2>/dev/null | tr -d '\r')" = "PONG" ]; then
			log "Redis 就绪"
			return 0
		fi
		sleep 0.5
	done
	log "Redis 30s 内未就绪"
	return 2
}

start_haproxy() {
	mkdir -p "$(dirname "$HAPROXY_CFG")"
	print_haproxy_config >"$HAPROXY_CFG"
	log "校验 HAProxy 配置"
	docker run --rm \
		-v "${HAPROXY_CFG}:/usr/local/etc/haproxy/haproxy.cfg:ro" \
		"$HAPROXY_IMAGE" \
		haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg || return 2
	log "启动 HAProxy：${HAPROXY_NAME}（host 网络，proxy=${PORT_PROXY} / stats=${PORT_STATS}）"
	docker run -d \
		--name "$HAPROXY_NAME" \
		--label "cluster3.run=${RUN_ID}" \
		--network host \
		-v "${HAPROXY_CFG}:/usr/local/etc/haproxy/haproxy.cfg:ro" \
		"$HAPROXY_IMAGE" >/dev/null || return 2
	local i
	for i in $(seq 1 60); do
		if curl -fsS -m 2 "http://127.0.0.1:${PORT_STATS}/stats" >/dev/null 2>&1; then
			log "HAProxy stats 就绪"
			return 0
		fi
		sleep 0.5
	done
	log "HAProxy 30s 内未就绪"
	return 2
}

extract_h2_jar() {
	mkdir -p "$RUN_DIR"
	local entry
	entry="$(unzip -Z1 "$JAR" 2>/dev/null | grep -E '^BOOT-INF/lib/h2-[0-9][^/]*\.jar$' | head -n1)"
	if [ -z "$entry" ]; then
		log "fat jar 中未找到 H2 库（BOOT-INF/lib/h2-*.jar）：$JAR"
		return 2
	fi
	unzip -p "$JAR" "$entry" >"$H2_JAR" || return 2
	if [ ! -s "$H2_JAR" ] || ! unzip -l "$H2_JAR" 2>/dev/null | grep -q 'org/h2/tools/Server.class'; then
		log "H2 jar 提取失败或缺少 org.h2.tools.Server：$H2_JAR"
		return 2
	fi
	log "H2 库已提取：$entry → $H2_JAR"
}

preflight_run() {
	if [ ! -f "$JAR" ]; then
		log "后端 jar 不存在：$JAR（先跑 mvn -B -DskipTests package）"
		exit 2
	fi
	local tool
	for tool in java node docker unzip curl; do
		if ! command -v "$tool" >/dev/null 2>&1; then
			log "缺少依赖：$tool"
			exit 2
		fi
	done
	JAVA_BIN="$(command -v java)"
	if ! docker info >/dev/null 2>&1; then
		log "Docker 守护进程不可用"
		exit 2
	fi
	local port
	for port in "$PORT_REDIS" "$PORT_PROXY" "$PORT_A" "$PORT_B" "$PORT_C" "$PORT_STATS" "$PORT_H2" "$PORT_STUB"; do
		require_port_free "$port"
	done
	# 统一成绝对路径：docker -v、H2 -baseDir 与 Java 参数都不接受含糊的相对路径。
	JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
	mkdir -p "$RUN_DIR" "$ARTIFACT_DIR"
	RUN_DIR="$(cd "$RUN_DIR" && pwd)"
	ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
	DB_BASE="$RUN_DIR/db"
	SHARED_FILES="$RUN_DIR/files"
	HAPROXY_CFG="$RUN_DIR/haproxy/haproxy.cfg"
	H2_JAR="$RUN_DIR/h2.jar"
	CI_LOG="$ARTIFACT_DIR/ci.log"
	mkdir -p "$ARTIFACT_DIR/instances" "$DB_BASE" "$SHARED_FILES"
	extract_h2_jar || exit 2
}

run_cluster() {
	# 边界：本网的真实三实例只在 GitHub Actions 的 runner 上跑。本机若确有需要，
	# 必须显式 CLUSTER3_ALLOW_LOCAL=1（默认拒绝，避免把本机环境当成 CI 证据）。
	# 这个判定必须在 RUN_MODE=1 之前：本机误触时不该创建/覆盖任何产物目录。
	if [ "${GITHUB_ACTIONS:-}" != "true" ] && [ "${CLUSTER3_ALLOW_LOCAL:-0}" != "1" ]; then
		log "拒绝在本机启动真实三实例：请用 --self-check；确需本机运行请设 CLUSTER3_ALLOW_LOCAL=1"
		exit 2
	fi
	RUN_MODE=1
	preflight_run
	exec > >(tee -a "$CI_LOG") 2>&1
	log "运行 ID：$RUN_ID"
	log "产物目录：$ARTIFACT_DIR；运行目录：$RUN_DIR"

	start_stub || exit 2
	start_redis || exit 2
	start_haproxy || exit 2

	local rc
	set +e
	node "$PROBE" --run \
		--artifacts "$ARTIFACT_DIR" \
		--run-dir "$RUN_DIR" \
		--jar "$JAR" \
		--h2-jar "$H2_JAR" \
		--db-base "$DB_BASE" \
		--shared-files "$SHARED_FILES" \
		--java "$JAVA_BIN" \
		--redis-container "$REDIS_NAME" \
		--haproxy-container "$HAPROXY_NAME" \
		--haproxy-config "$HAPROXY_CFG" \
		--stub-url "http://127.0.0.1:${PORT_STUB}" \
		--proxy-url "http://127.0.0.1:${PORT_PROXY}" \
		--stats-url "http://127.0.0.1:${PORT_STATS}/stats;csv" \
		--ports "a=${PORT_A},b=${PORT_B},c=${PORT_C},h2=${PORT_H2},redis=${PORT_REDIS}" \
		--ready-timeout 180 \
		--job-fail-timeout 130 \
		--kill-takeover-timeout 120
	rc=$?
	set -e
	if [ "$rc" -eq 0 ]; then
		log "三实例验证：全绿"
	elif [ "$rc" -eq 2 ]; then
		log "三实例验证：环境/基线错误（见 summary.md / ci.log）"
	else
		log "三实例验证：存在 FAIL（见 summary.md / results.json）"
	fi
	exit "$rc"
}

main() {
	case "${1:-}" in
	--self-check)
		self_check
		exit $?
		;;
	--print-haproxy-config)
		print_haproxy_config
		exit 0
		;;
	"" )
		run_cluster
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		printf '未知参数：%s\n' "$1" >&2
		usage >&2
		exit 2
		;;
	esac
}

trap cleanup EXIT
trap 'exit 130' INT TERM
main "$@"
