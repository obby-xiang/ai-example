#!/usr/bin/env bash
# Redis 5.0.14 真集群冒烟网（3 主 3 从）
#
# 设计依据：docs/research/排查-A4-Cluster与5.0代码级兼容穷尽-2026-10-24.md §十「批次 0」。
#
# 用法：
#   bash scripts/redis5-cluster-smoke.sh            # 真集群（需要 Docker；CI 用这个）
#   bash scripts/redis5-cluster-smoke.sh --static   # 只跑静态检查（无 Docker 也能跑）
#
# 退出码：0 = 检查全绿；1 = 有 FAIL；2 = 环境/参数错误。
#
# 证据目录（默认 backend/target/redis5-smoke，已被 .gitignore 的 backend/target/ 覆盖）：
#   summary.md / commands.log / cluster-*.txt / failover-*.txt / log-scan.txt / redis-*.log /
#   pubsub-subscriber.log / slot-map.txt
#
# 口径（必须如实交代）：
#   - 本脚本的“真实集群证据”只在真集群模式成立；--static 模式不连接任何 Redis，
#     不得用来声称“5.0.14 集群已验证”。
#   - 故障注入只断言确定性事实（死节点探针的连接失败证据、存活主节点 PONG、超时内收敛、收敛后可写读），
#     不对产品 RedisAvailability 的 available 取值做瞬时单值预期 —— 那取决于连接落点与重连时序，
#     是 flaky 源，也不是本网能替产品断言的。分阶段原始结果全部落盘备查。
#   - 设计文档 §十 批次 0 ⑤ 关注的是产品 RedisAvailability.available 的取值（单主 kill 下）。
#     该口径**未在本网验证**：本网只有环境级 redis-cli 证据；产品 available/503 需产品链路或单测后续测。
#     节点 docker logs 只落盘备查，不设门禁（避免 MASTER<->REPLICA、节点失败等正常行误伤）；
#     窄门禁只针对 commands.log 里三条预期探针之外的错误。
#   - 只使用公共镜像 redis:5.0.14；不注入任何密钥；容器间走 user-defined network，
#     **不映射任何宿主端口**（从根上消除端口冲突）。
#   - 产品契约门是“修前红 / 修后绿”的验收点：未修代码使用 Range.Bound.exclusive
#     （HEAD 版本命中 RedisFrameStore.java:299），修成 from+1 闭区间后自动转绿；
#     真集群侧的 ① 负责证明 5.0.14 确实拒绝排他区间（6.2 才支持）。

set -u -o pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT" || exit 2

ARTIFACT_DIR="${SMOKE_ARTIFACT_DIR:-backend/target/redis5-smoke}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:5.0.14}"
EXPECTED_VERSION="5.0.14"

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$-${RANDOM}"
PREFIX="r5-${RUN_ID}"
NET="${PREFIX}-net"
NODES=()

PASS_N=0
FAIL_N=0
KNOWN_N=0
SKIP_N=0
RESULTS=()
SUMMARY_WRITTEN=0
REAL_MODE=0
MODE_LABEL="unknown"
TOPOLOGY_LABEL="未连接（静态模式）"
REAL_LABEL="否（静态检查）"
XRANGE_GATE_LABEL="未执行"
COMMANDS_LOG=""

usage() {
	cat <<'EOF'
用法: bash scripts/redis5-cluster-smoke.sh [--static]

  (无参数)    拉起真实 Redis 5.0.14 3 主 3 从并跑全部检查（需要 Docker）
  --static    只跑静态检查：产品 XRANGE 契约门 + 文件卫生（无绝对路径/密钥/CRLF）
  -h, --help  显示本帮助
EOF
}

# ── 结果记录 ────────────────────────────────────────────────────────────────

record() {
	local status=$1
	local name=$2
	local note="${3:-}"
	note=${note//|/\\|}
	note=${note//$'\n'/ }
	RESULTS+=("${status}|${name}|${note}")
}

pass() {
	PASS_N=$((PASS_N + 1))
	printf 'PASS    %s\n' "$1"
	record PASS "$1" "${2:-}"
}

fail() {
	FAIL_N=$((FAIL_N + 1))
	printf 'FAIL    %s\n' "$1"
	record FAIL "$1" "${2:-}"
}

known() {
	KNOWN_N=$((KNOWN_N + 1))
	printf 'KNOWN-LIMIT  %s\n' "$1"
	record KNOWN-LIMIT "$1" "${2:-}"
}

skip() {
	SKIP_N=$((SKIP_N + 1))
	printf 'SKIP    %s\n' "$1"
	record SKIP "$1" "${2:-}"
}

fatal() {
	fail "$1" "${2:-}"
	exit 2
}

section() {
	printf '\n===== %s =====\n' "$1"
	printf '\n===== %s =====\n' "$1" >>"$COMMANDS_LOG"
}

assert_eq() {
	if [ "$1" = "$2" ]; then
		pass "$3" "${4:-}"
	else
		fail "$3" "期望 [$2]，实际 [$1]${4:+；$4}"
	fi
}

assert_contains() {
	local text=$1 needle=$2 name=$3 note=${4:-}
	if printf '%s' "$text" | grep -qF -- "$needle"; then
		pass "$name" "$note"
	else
		fail "$name" "期望包含 [$needle]，实际: $(printf '%s' "$text" | head -2 | tr '\n' ' ')"
	fi
}

assert_contains_ci() {
	local text=$1 needle=$2 name=$3 note=${4:-}
	if printf '%s' "$text" | grep -qiF -- "$needle"; then
		pass "$name" "$note"
	else
		fail "$name" "期望包含 [$needle]（忽略大小写），实际: $(printf '%s' "$text" | head -2 | tr '\n' ' ')"
	fi
}

info_field() {
	printf '%s\n' "$1" | tr -d '\r' | awk -F: -v key="$2" '$1 == key {print $2; exit}'
}

now_ms() {
	if date +%s%3N >/dev/null 2>&1; then
		date +%s%3N
	else
		python3 -c 'import time; print(int(time.time() * 1000))'
	fi
}

# ── 命令封装 ────────────────────────────────────────────────────────────────

log_cmd() {
	printf '\n$ %s\n' "$*" >>"$COMMANDS_LOG"
}

_rcli_capture() {
	local mode=$1
	local container=$2
	shift 2
	local args=(-p 6379)
	if [ "$mode" = "cluster" ]; then
		args+=(-c)
	fi
	log_cmd "docker exec $container redis-cli ${args[*]} $*"
	RCLI_OUT="$(docker exec "$container" redis-cli "${args[@]}" "$@" 2>&1)"
	RCLI_RC=$?
	printf '%s\n' "$RCLI_OUT" >>"$COMMANDS_LOG"
}

rcli_capture() { _rcli_capture plain "$@"; }
rcli_capture_c() { _rcli_capture cluster "$@"; }

with_timeout() {
	local secs=$1
	shift
	if command -v timeout >/dev/null 2>&1; then
		timeout "$secs" "$@"
	else
		"$@"
	fi
}

# 死节点探针的“强证据”：redis-cli 连接失败 rc=1，timeout 杀掉 rc=124；
# 输出必须命中 refused / timed out / Could not connect 之一。
# rc=124 + 空输出的“弱证据”由调用方单列（容器已停止但 10s 无响应），不归本函数。
is_expected_connect_failure() {
	local rc=$1
	local out=$2
	case "$rc" in
	1 | 124) ;;
	*) return 1 ;;
	esac
	printf '%s' "$out" | grep -qiE 'refused|timed out|Could not connect'
}

_rcli_net_capture() {
	local ip=$1
	local port=$2
	shift 2
	log_cmd "docker run --rm --network $NET --entrypoint redis-cli $REDIS_IMAGE -h $ip -p $port $*"
	RCLI_OUT="$(with_timeout 10 docker run --rm \
		--network "$NET" \
		--label "redis5smoke.run=$RUN_ID" \
		--label "redis5smoke=1" \
		--entrypoint redis-cli "$REDIS_IMAGE" \
		-h "$ip" -p "$port" "$@" 2>&1)"
	RCLI_RC=$?
	printf '%s\n' "$RCLI_OUT" >>"$COMMANDS_LOG"
}

rping() {
	docker exec "$1" redis-cli -p 6379 PING 2>/dev/null | tr -d '\r'
}

rcluster_info() {
	docker exec "$1" redis-cli -p 6379 CLUSTER INFO 2>/dev/null | tr -d '\r'
}

rcluster_nodes() {
	docker exec "$1" redis-cli -p 6379 CLUSTER NODES 2>/dev/null | tr -d '\r'
}

container_ip() {
	docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1" 2>/dev/null
}

container_for_ip() {
	local want=$1 c ip
	for c in "${NODES[@]}"; do
		ip=$(container_ip "$c")
		if [ "$ip" = "$want" ]; then
			printf '%s' "$c"
			return 0
		fi
	done
	return 1
}

line_for_id() {
	printf '%s\n' "$1" | awk -v id="$2" '$1 == id {print; exit}'
}

node_addr_ip() {
	printf '%s' "$1" | awk '{print $2}' | sed 's/@.*//' | cut -d: -f1
}

node_addr_port() {
	printf '%s' "$1" | awk '{print $2}' | sed 's/@.*//' | cut -d: -f2
}

# 从 “槽区间串 + 键槽映射文件（"<slot> <key>" 每行一条）” 里挑第一个落在区间内的键。
# 区间串形如 "0-5460 10923-16383 "（CLUSTER NODES 的槽字段，尾随空格由调用方保留）。
pick_key_in_slots() {
	local ranges=$1
	local map_file=$2
	awk -v ranges="$ranges" '
		BEGIN {
			n = 0
			m = split(ranges, a, " ")
			for (j = 1; j <= m; j++) {
				if (a[j] !~ /^[0-9]+(-[0-9]+)?$/) continue
				n++
				split(a[j], r, "-")
				lo[n] = r[1] + 0
				hi[n] = (r[2] == "" ? r[1] + 0 : r[2] + 0)
			}
		}
		{
			if (NF < 2) next
			s = $1 + 0
			for (i = 1; i <= n; i++) {
				if (s >= lo[i] && s <= hi[i]) { print $2; exit }
			}
		}' "$map_file"
}

# 故障分阶段 TSV：早退（kill 未生效/无法确认）也要留下决定性事实。
write_failover_stages() {
	local rows=$1
	{
		printf 'stage\tat_ms\trel_ms\ttarget\tresult\n'
		printf '%s' "$rows"
	} >"$ARTIFACT_DIR/failover-stages.tsv"
}

# ── 清理 ────────────────────────────────────────────────────────────────────

write_summary() {
	[ "$SUMMARY_WRITTEN" = "1" ] && return 0
	mkdir -p "$ARTIFACT_DIR" 2>/dev/null || return 0
	{
		echo "## Redis 5.0.14 集群冒烟网"
		echo
		echo "| 项 | 值 |"
		echo "|---|---|"
		echo "| 模式 | $MODE_LABEL |"
		echo "| 镜像 | \`$REDIS_IMAGE\` |"
		echo "| 拓扑 | $TOPOLOGY_LABEL |"
		echo "| 真实集群证据 | $REAL_LABEL |"
		echo "| 产品 XRANGE 契约门 | $XRANGE_GATE_LABEL |"
		echo "| 产品 RedisAvailability.available（设计文档 §十 批次 0 ⑤） | **未在本网验证**；需产品链路/单测后续测 |"
		echo "| 结果 | PASS $PASS_N / FAIL $FAIL_N / KNOWN-LIMIT $KNOWN_N / SKIP $SKIP_N |"
		echo "| 时间（UTC） | $(date -u '+%Y-%m-%dT%H:%M:%SZ') |"
		echo
		echo "| # | 检查 | 结果 | 证据 |"
		echo "|---:|---|---|---|"
		local i=1 row status name note
		for row in ${RESULTS[@]+"${RESULTS[@]}"}; do
			IFS='|' read -r status name note <<<"$row"
			echo "| $i | $name | $status | $note |"
			i=$((i + 1))
		done
		echo
		if [ "$FAIL_N" -gt 0 ]; then
			echo "**结论：有 $FAIL_N 项失败；KNOWN-LIMIT 与 SKIP 均不计入通过（见下表）。**"
		else
			echo "**结论：无 FAIL；KNOWN-LIMIT 与 SKIP 均不计入通过，SKIP 明细见下表。**"
		fi
		if [ "$REAL_MODE" != "1" ]; then
			echo
			echo "> 本次为 **静态检查**，未连接任何 Redis 集群；不得作为「真实 5.0.14 集群已验证」的证据。"
		fi
	} >"$ARTIFACT_DIR/summary.md"
	SUMMARY_WRITTEN=1
}

cleanup() {
	local rc=$?
	trap - EXIT INT TERM
	if [ "$REAL_MODE" = "1" ]; then
		printf '\n清理：删除本次运行创建的容器与网络...\n'
		local ids left net_left
		ids="$(docker ps -aq --filter "label=redis5smoke.run=$RUN_ID" 2>/dev/null || true)"
		if [ -n "$ids" ]; then
			# shellcheck disable=SC2086
			docker rm -f $ids >/dev/null 2>&1 || true
		fi
		docker network rm "$NET" >/dev/null 2>&1 || true
		left="$(docker ps -aq --filter "label=redis5smoke.run=$RUN_ID" 2>/dev/null || true)"
		net_left="$(docker network ls -q --filter "label=redis5smoke.run=$RUN_ID" 2>/dev/null || true)"
		if [ -n "$left" ] || [ -n "$net_left" ]; then
			printf 'WARN    清理后仍有残留：容器=[%s] 网络=[%s]\n' "$left" "$net_left"
		else
			printf '清理完成（无残留容器/网络）\n'
		fi
	fi
	write_summary
	exit "$rc"
}

trap cleanup EXIT
trap 'exit 130' INT TERM

# ── 静态检查 ────────────────────────────────────────────────────────────────

check_product_xrange_contract() {
	section "产品契约门（静态）：含 .xRange( 的主代码文件不得使用 Redis 5.0.14 不支持的排他区间"
	if [ ! -d backend/src/main/java ]; then
		XRANGE_GATE_LABEL="FAIL（后端主代码目录缺失）"
		fail "后端主代码目录不存在" "backend/src/main/java"
		return
	fi
	# 只针对真正调用 .xRange( 的文件，且只认当前已知的构造方式
	# （Spring Data 的 Range.Bound.exclusive）；避免把日期区间等合法排他用法误判成 XRANGE。
	# 若产品改用其它方式构造排他区间（例如自拼 "(" 前缀），本门需同步扩展；
	# 真集群侧的 ① 已经把“5.0.14 拒绝排他区间”钉成环境事实。
	local xfiles f h hits=""
	xfiles="$(grep -rlE --include='*.java' '\.xRange\(' backend/src/main/java 2>/dev/null || true)"
	if [ -z "$xfiles" ]; then
		XRANGE_GATE_LABEL="**失效：未发现 .xRange( 调用，需人工确认**"
		skip "契约门失效：未发现 .xRange( 调用，需人工确认（实现若迁移需同步更新本门）" "backend/src/main/java"
		return
	fi
	while IFS= read -r f; do
		[ -z "$f" ] && continue
		h="$(grep -n 'Range\.Bound\.exclusive' "$f" 2>/dev/null || true)"
		if [ -n "$h" ]; then
			hits+="${f}:${h};"
		fi
	done <<<"$xfiles"
	if [ -n "$hits" ]; then
		XRANGE_GATE_LABEL="FAIL"
		fail "含 .xRange( 的主代码仍使用 XRANGE 排他区间（Range.Bound.exclusive）：Redis 5.0.14 会报 Invalid stream ID" \
			"命中: $(printf '%s' "$hits" | head -3 | tr '\n' ' ')；修复方向：from+1 的闭区间或读侧过滤"
	else
		XRANGE_GATE_LABEL="PASS"
		pass "含 .xRange( 的主代码未使用 XRANGE 排他区间"
	fi
}

check_file_hygiene() {
	section "文件卫生（静态）：无本机绝对路径 / 无密钥字面量 / LF 行尾"
	local f path_hits secret_hits
	for f in scripts/redis5-cluster-smoke.sh .github/workflows/redis-5-smoke.yml; do
		if [ ! -f "$f" ]; then
			skip "文件不存在，跳过卫生检查" "$f"
			continue
		fi
		if grep -q $'\r' "$f"; then
			fail "文件含 CRLF 行尾" "$f"
		else
			pass "LF 行尾" "$f"
		fi
		path_hits="$(grep -nE '(^|[^A-Za-z0-9])[A-Za-z]:[\\/]|[/]Users[/]|[/]home[/][A-Za-z0-9._-]+[/]' "$f" 2>/dev/null || true)"
		if [ -n "$path_hits" ]; then
			fail "文件含本机绝对路径" "$(printf '%s\n' "$path_hits" | head -3 | tr '\n' ';')"
		else
			pass "无本机绝对路径" "$f"
		fi
		secret_hits="$(grep -nE 'AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----' "$f" 2>/dev/null || true)"
		if [ -n "$secret_hits" ]; then
			fail "文件疑似含密钥字面量" "$f"
		else
			pass "无密钥字面量" "$f"
		fi
	done
}

# ── 真集群：启动与拓扑 ──────────────────────────────────────────────────────

start_cluster() {
	section "启动 Redis 5.0.14 集群（3 主 3 从，零宿主端口映射）"

	if ! command -v docker >/dev/null 2>&1; then
		fatal "未找到 docker：真集群模式需要 Docker；只做静态检查请加 --static"
	fi
	if ! docker info >/dev/null 2>&1; then
		fatal "Docker 守护进程不可用（docker info 失败）"
	fi

	if ! docker image inspect "$REDIS_IMAGE" >/dev/null 2>&1; then
		printf '拉取镜像 %s ...\n' "$REDIS_IMAGE"
		docker pull "$REDIS_IMAGE" || fatal "镜像拉取失败" "$REDIS_IMAGE"
	fi

	local ver_out
	ver_out="$(docker run --rm \
		--label "redis5smoke.run=$RUN_ID" \
		--label "redis5smoke=1" \
		--entrypoint redis-server "$REDIS_IMAGE" --version 2>&1)"
	if printf '%s' "$ver_out" | grep -q "v=${EXPECTED_VERSION}"; then
		pass "镜像版本 = ${EXPECTED_VERSION}" "$(printf '%s' "$ver_out" | head -1)"
	else
		fatal "镜像版本不是 ${EXPECTED_VERSION}，冒烟结论不可用" "$ver_out"
	fi

	docker network create \
		--label "redis5smoke.run=$RUN_ID" \
		--label "redis5smoke=1" \
		"$NET" >/dev/null || fatal "创建 Docker 网络失败" "$NET"

	local i name
	for i in 1 2 3 4 5 6; do
		name="${PREFIX}-n${i}"
		docker run -d \
			--name "$name" \
			--hostname "$name" \
			--network "$NET" \
			--label "redis5smoke.run=$RUN_ID" \
			--label "redis5smoke=1" \
			--entrypoint redis-server "$REDIS_IMAGE" \
			--port 6379 \
			--bind 0.0.0.0 \
			--protected-mode no \
			--cluster-enabled yes \
			--cluster-config-file nodes.conf \
			--cluster-node-timeout 5000 \
			--appendonly no \
			>/dev/null || fatal "启动容器失败" "$name"
		NODES+=("$name")
	done

	local ok
	for name in "${NODES[@]}"; do
		ok=0
		for i in $(seq 1 30); do
			if [ "$(rping "$name")" = "PONG" ]; then
				ok=1
				break
			fi
			sleep 1
		done
		if [ "$ok" = "1" ]; then
			pass "节点就绪" "$name"
		else
			fatal "节点 30s 内未响应 PING" "$name"
		fi
	done

	local mapped=0
	for name in "${NODES[@]}"; do
		if [ -n "$(docker port "$name" 2>/dev/null)" ]; then
			mapped=1
			printf 'WARN    节点映射了宿主端口: %s -> %s\n' "$name" "$(docker port "$name")"
		fi
	done
	if [ "$mapped" = "0" ]; then
		pass "6 节点均无宿主端口映射（无端口冲突面）"
	else
		fail "存在宿主端口映射，端口冲突风险"
	fi

	# 5.0.14 的 clusterManagerWaitForClusterJoin() 没有总超时；这里给 240s 硬上限，
	# 避免总线不通时白等 workflow 的 25 分钟。with_timeout 的参数是秒数（10s 那套只给死节点探针）。
	local create_out create_rc
	create_out="$(with_timeout 240 docker exec "${NODES[0]}" redis-cli --cluster create \
		"${NODES[0]}:6379" \
		"${NODES[1]}:6379" \
		"${NODES[2]}:6379" \
		"${NODES[3]}:6379" \
		"${NODES[4]}:6379" \
		"${NODES[5]}:6379" \
		--cluster-replicas 1 --cluster-yes 2>&1)"
	create_rc=$?
	printf '%s\n' "$create_out" >"$ARTIFACT_DIR/cluster-create.txt"
	if [ "$create_rc" -eq 124 ]; then
		fail "集群创建 240s 超时（不是等 job 25 分钟）" "$(printf '%s' "$create_out" | tail -2 | tr '\n' ' ')"
	elif printf '%s' "$create_out" | grep -q "All 16384 slots covered"; then
		pass "集群创建：16384 槽全覆盖" "3 主 3 从（--cluster-replicas 1）"
	else
		fail "集群创建失败（rc=$create_rc）" "$(printf '%s' "$create_out" | tail -3 | tr '\n' ' ')"
	fi

	local info=""
	for i in $(seq 1 90); do
		info="$(rcluster_info "${NODES[0]}")"
		if [ "$(info_field "$info" cluster_state)" = "ok" ] \
			&& [ "$(info_field "$info" cluster_slots_assigned)" = "16384" ] \
			&& [ "$(info_field "$info" cluster_known_nodes)" = "6" ]; then
			break
		fi
		sleep 1
	done
	if [ "$(info_field "$info" cluster_state)" = "ok" ] \
		&& [ "$(info_field "$info" cluster_slots_assigned)" = "16384" ] \
		&& [ "$(info_field "$info" cluster_known_nodes)" = "6" ]; then
		pass "集群状态 ok，16384 槽已分配，6 节点已知" "cluster_size=$(info_field "$info" cluster_size)"
	else
		fatal "集群 90s 内未就绪" "$info"
	fi

	rcli_capture "${NODES[0]}" CLUSTER NODES
	RCLI_OUT=$(printf '%s' "$RCLI_OUT" | tr -d '\r')
	printf '%s\n' "$RCLI_OUT" >"$ARTIFACT_DIR/cluster-nodes-before.txt"
	local nodes=$RCLI_OUT
	printf '%s\n' "$info" >"$ARTIFACT_DIR/cluster-info-before.txt"

	local masters slaves mid replica_n master_count slave_count
	masters=$(printf '%s\n' "$nodes" | awk '$3 ~ /master/ && $3 !~ /fail/ && NF >= 9 {print $1}')
	slaves=$(printf '%s\n' "$nodes" | awk '$3 ~ /slave|replica/ {print $1}')
	master_count=$(printf '%s\n' "$masters" | grep -c . || true)
	slave_count=$(printf '%s\n' "$slaves" | grep -c . || true)
	assert_eq "$master_count" "3" "主节点数 = 3（不是 2 主）"
	assert_eq "$slave_count" "3" "从节点数 = 3"
	assert_eq "$(info_field "$info" cluster_known_nodes)" "6" "cluster_known_nodes = 6"
	assert_eq "$(info_field "$info" cluster_size)" "3" "cluster_size = 3"

	for mid in $masters; do
		replica_n=$(printf '%s\n' "$nodes" | awk -v m="$mid" '$4 == m && $3 ~ /slave|replica/ {c++} END {print c + 0}')
		if [ "$replica_n" = "1" ]; then
			pass "主节点各有 1 个从" "$mid"
		else
			fail "主节点从节点数不是 1" "$mid -> $replica_n"
		fi
	done

	rcli_capture "${NODES[0]}" INFO server
	local version
	version=$(info_field "$RCLI_OUT" redis_version)
	assert_eq "$version" "$EXPECTED_VERSION" "服务端 redis_version = ${EXPECTED_VERSION}"

	TOPOLOGY_LABEL="3 主 3 从（cluster_size=3 / known_nodes=$(info_field "$info" cluster_known_nodes)）"
	REAL_LABEL="是（本脚本实际拉起 ${EXPECTED_VERSION} 集群）"
}

# ── ① XRANGE ────────────────────────────────────────────────────────────────

check_xrange() {
	section "① XRANGE：排他区间（5.0.14 预期失败）与闭区间等价"
	local key="ai:xevents:smoke-${RUN_ID}"
	local s ids first last n

	rcli_capture_c "${NODES[0]}" DEL "$key"
	for s in 1 2 3 4; do
		rcli_capture_c "${NODES[0]}" XADD "$key" "${s}-0" seq "$s" frame "f${s}"
	done
	assert_eq "$RCLI_OUT" "4-0" "XADD 显式 ID 可用（末条 4-0）"

	# 产品的目标语义是“只取 > afterSeq”。排他写法在 5.0.14 上必然失败；
	# 这条 KNOWN-LIMIT 不是放过缺陷，而是把“环境限制”钉在证据里；
	# 真正的红项是产品契约门（代码仍在用排他区间时）。
	rcli_capture_c "${NODES[0]}" XRANGE "$key" "(2-0" "+"
	if printf '%s' "$RCLI_OUT" | grep -qi "Invalid stream ID"; then
		known "XRANGE 排他区间在 5.0.14 上按预期失败（环境限制已复现）" \
			"$(printf '%s' "$RCLI_OUT" | head -1)"
	else
		fail "XRANGE 排他区间未按预期失败（镜像可能不是 5.0.14）" \
			"$(printf '%s' "$RCLI_OUT" | head -1)"
	fi

	# 闭区间等价：>2-0 等价于从 3-0 开始（产品修复方向 from+1）。
	rcli_capture_c "${NODES[0]}" --raw XRANGE "$key" "3-0" "+"
	ids=$(printf '%s\n' "$RCLI_OUT" | grep -E '^[0-9]+-[0-9]+$' || true)
	n=$(printf '%s\n' "$ids" | grep -c . || true)
	assert_eq "$n" "2" "闭区间 XRANGE 3-0 + 返回 2 条（与 >2-0 等价）"
	first=$(printf '%s\n' "$ids" | head -1)
	last=$(printf '%s\n' "$ids" | tail -1)
	assert_eq "$first" "3-0" "闭区间首条 = 3-0"
	assert_eq "$last" "4-0" "闭区间末条 = 4-0"
	if printf '%s\n' "$ids" | grep -qx '2-0'; then
		fail "闭区间起点不应包含 2-0" "$ids"
	else
		pass "闭区间起点不含 2-0（无重复帧）"
	fi

	# 反向对照：2-0 + 含起点共 3 条，说明“排他”必须 +1 才能等价。
	rcli_capture_c "${NODES[0]}" --raw XRANGE "$key" "2-0" "+"
	n=$(printf '%s\n' "$RCLI_OUT" | grep -cE '^[0-9]+-[0-9]+$' || true)
	assert_eq "$n" "3" "XRANGE 2-0 + 含起点共 3 条（排他需 from+1）"

	rcli_capture_c "${NODES[0]}" --raw XRANGE "$key" "1-0" "+" COUNT 2
	n=$(printf '%s' "$RCLI_OUT" | grep -cE '^[0-9]+-[0-9]+$' || true)
	assert_eq "$n" "2" "XRANGE ... COUNT 2 在 5.0.14 可用"
}

# ── ② CROSSSLOT ─────────────────────────────────────────────────────────────

check_crossslot() {
	section "② 跨槽 CROSSSLOT 与 hash tag"
	local k1="" k2="" t1="{run:x-${RUN_ID}}:ai:run" t2="{run:x-${RUN_ID}}:ai:seq"
	local s1="" s2="" ts1 ts2 i
	# 两键是否异槽由哈希决定；1/16384 概率会撞槽，改用有限重试，避免随机红。
	for i in 1 2 3 4 5; do
		k1="ai:run:x-${RUN_ID}-${i}"
		k2="ai:seq:x-${RUN_ID}-${i}"
		rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$k1"; s1=$RCLI_OUT
		rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$k2"; s2=$RCLI_OUT
		if [ "$s1" != "$s2" ]; then
			break
		fi
	done
	if [ "$s1" != "$s2" ]; then
		pass "无 tag 两键落在不同槽" "$k1 -> $s1 / $k2 -> $s2（第 $i 次尝试）"
	else
		fail "无 tag 两键 5 次尝试均意外同槽" "$k1 / $k2 -> $s1"
	fi

	rcli_capture_c "${NODES[0]}" MSET "$k1" v1 "$k2" v2
	assert_contains_ci "$RCLI_OUT" "CROSSSLOT" "无 tag MSET 被 CROSSSLOT 拒绝"

	rcli_capture_c "${NODES[0]}" EVAL "return redis.call('GET', KEYS[1])" 2 "$k1" "$k2"
	assert_contains_ci "$RCLI_OUT" "CROSSSLOT" "无 tag 双键 Lua 被 CROSSSLOT 拒绝"

	rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$t1"; ts1=$RCLI_OUT
	rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$t2"; ts2=$RCLI_OUT
	if [ "$ts1" = "$ts2" ]; then
		pass "加 tag 后两键同槽" "$t1 / $t2 -> $ts1"
	else
		fail "加 tag 后两键仍不同槽" "$ts1 / $ts2"
	fi

	rcli_capture_c "${NODES[0]}" MSET "$t1" v1 "$t2" v2
	assert_eq "$RCLI_OUT" "OK" "加 tag 后 MSET 成功"
	rcli_capture_c "${NODES[0]}" EVAL "return redis.call('GET', KEYS[1])" 2 "$t1" "$t2"
	assert_eq "$RCLI_OUT" "v1" "加 tag 后双键 Lua 成功"
}

# ── ③ DEL 语义 ──────────────────────────────────────────────────────────────

check_del_semantics() {
	section "③ DEL 语义：加 tag 前（跨槽、N 条命令）vs 加 tag 后（同槽、单命令）"
	local prefixes=(ai:run ai:pending ai:claim ai:ledger ai:idem ai:events ai:seq ai:xevents ai:lease:run ai:fence:run ai:req ai:decided)
	local untagged=() tagged=()
	local p k slots=() tslots=()
	local distinct tdistinct sum

	for p in "${prefixes[@]}"; do
		untagged+=("${p}:del-${RUN_ID}")
		tagged+=("{run:del-${RUN_ID}}:${p}")
	done

	for k in "${untagged[@]}"; do
		rcli_capture_c "${NODES[0]}" SET "$k" x
	done
	for k in "${untagged[@]}"; do
		rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$k"
		slots+=("$RCLI_OUT")
	done
	distinct=$(printf '%s\n' "${slots[@]}" | sort -u | wc -l | tr -d ' ')

	if [ "$distinct" -gt 1 ]; then
		pass "无 tag 的 12 键分布在 $distinct 个槽（单命令必然跨槽）"
	else
		fail "无 tag 的 12 键意外集中在 1 个槽" "$distinct"
	fi

	# raw redis-cli 单命令 DEL 会吃到 CROSSSLOT；产品侧不是这个行为：
	# Lettuce RedisAdvancedClusterCommands.del(K...) 会按槽拆成多次单键 DEL ⇒ 不报错、但失原子性。
	# 下面这条是“raw 语义”的对照；逐键 12 次的断言才是产品等价面。
	rcli_capture_c "${NODES[0]}" DEL "${untagged[@]}"
	assert_contains_ci "$RCLI_OUT" "CROSSSLOT" "raw 单命令 DEL 12 键被 CROSSSLOT 拒绝" \
		"产品 Lettuce 按槽拆分 ⇒ 不报错、失原子性；逐键 12 次即其等价面"

	sum=0
	for k in "${untagged[@]}"; do
		rcli_capture_c "${NODES[0]}" DEL "$k"
		sum=$((sum + RCLI_OUT))
	done
	assert_eq "$sum" "12" "逐键 DEL 合计 12（12 条命令跨 $distinct 槽，无原子性）"

	for k in "${tagged[@]}"; do
		rcli_capture_c "${NODES[0]}" SET "$k" x
	done
	for k in "${tagged[@]}"; do
		rcli_capture_c "${NODES[0]}" CLUSTER KEYSLOT "$k"
		tslots+=("$RCLI_OUT")
	done
	tdistinct=$(printf '%s\n' "${tslots[@]}" | sort -u | wc -l | tr -d ' ')
	assert_eq "$tdistinct" "1" "加 tag 后 12 键同槽"

	rcli_capture_c "${NODES[0]}" DEL "${tagged[@]}"
	assert_eq "$RCLI_OUT" "12" "加 tag 后单命令 DEL 12 键返回 12（原子）"
	rcli_capture_c "${NODES[0]}" DEL "${tagged[@]}"
	assert_eq "$RCLI_OUT" "0" "重复 DEL 返回 0（DEL 语义）"
	rcli_capture_c "${NODES[0]}" EXISTS "${tagged[@]}"
	assert_eq "$RCLI_OUT" "0" "加 tag 的 12 键已全部删除"
}

# ── ④ Pub/Sub ───────────────────────────────────────────────────────────────

check_pubsub() {
	section "④ Pub/Sub：跨节点投递"
	local channel="ai:wake:run-${RUN_ID}"
	local sub="${PREFIX}-sub"
	local pub_ret logs="" ok ready=0

	docker run -d \
		--name "$sub" \
		--network "$NET" \
		--label "redis5smoke.run=$RUN_ID" \
		--label "redis5smoke=1" \
		--entrypoint redis-cli "$REDIS_IMAGE" \
		--raw -h "${NODES[0]}" -p 6379 SUBSCRIBE "$channel" >/dev/null \
		|| { fail "订阅容器启动失败" "$sub"; return; }

	for i in $(seq 1 20); do
		if docker logs "$sub" 2>&1 | grep -qx 'subscribe'; then
			ready=1
			break
		fi
		sleep 0.5
	done
	if [ "$ready" != "1" ]; then
		logs="$(docker logs "$sub" 2>&1)"
		printf '%s\n' "$logs" >"$ARTIFACT_DIR/pubsub-subscriber.log"
		fail "订阅 10s 内未就绪（未出现 subscribe 确认），不继续 PUBLISH" \
			"$(printf '%s' "$logs" | tail -3 | tr '\n' ' ')"
		docker rm -f "$sub" >/dev/null 2>&1 || true
		return
	fi

	rcli_capture "${NODES[1]}" PUBLISH "$channel" "42"
	pub_ret=$RCLI_OUT

	ok=0
	for i in $(seq 1 20); do
		logs="$(docker logs "$sub" 2>&1)"
		if printf '%s\n' "$logs" | grep -qx '42'; then
			ok=1
			break
		fi
		sleep 0.5
	done
	printf '%s\n' "$logs" >"$ARTIFACT_DIR/pubsub-subscriber.log"

	if [ "$ok" = "1" ]; then
		pass "订阅在 ${NODES[0]}、发布在 ${NODES[1]}：消息跨节点到达" \
			"PUBLISH 返回值=$pub_ret（集群口径不同版本可能只计发布节点本地接收者，不作单值断言）"
	else
		fail "消息未在 10s 内到达跨节点订阅者" \
			"PUBLISH 返回值=$pub_ret；订阅日志: $(printf '%s' "$logs" | tail -3 | tr '\n' ' ')"
	fi

	if printf '%s\n' "$logs" | grep -qx "$channel"; then
		pass "订阅日志含频道名" "$channel"
	else
		fail "订阅日志缺少频道名" "$channel"
	fi
	if printf '%s\n' "$logs" | grep -qx 'message'; then
		pass "订阅日志含 message 事件"
	else
		fail "订阅日志缺少 message 事件"
	fi

	rcli_capture "${NODES[0]}" PUBSUB CHANNELS "$channel"
	assert_contains "$RCLI_OUT" "$channel" "订阅所在节点的 PUBSUB CHANNELS 可见该频道"

	docker rm -f "$sub" >/dev/null 2>&1 || true
}

# ── ⑤ TIME / CLIENT SETINFO ─────────────────────────────────────────────────

check_time_and_setinfo() {
	section "⑤ TIME（含 Lua 写后置）与 CLIENT SETINFO 兼容性"
	local lua out nums n

	rcli_capture_c "${NODES[0]}" --raw TIME
	nums=$(printf '%s\n' "$RCLI_OUT" | grep -cE '^[0-9]+$' || true)
	if [ "$nums" -ge 2 ]; then
		pass "TIME 直接调用返回秒/微秒两个值" "$(printf '%s' "$RCLI_OUT" | tr '\n' ' ')"
	else
		fail "TIME 直接调用返回值异常" "$RCLI_OUT"
	fi

	# 与 AiRateLimiter.SLIDING_WINDOW 同形：先 TIME，再写（ZREMRANGEBYSCORE/ZADD/PEXPIRE）。
	# 5.0 起脚本默认按效果复制，理论上允许；这条是“理论可用”的实测。
	lua="local t = redis.call('TIME'); redis.call('SET', KEYS[1], t[1]); return redis.call('GET', KEYS[1])"
	rcli_capture_c "${NODES[0]}" --raw EVAL "$lua" 1 "ai:rate:smoke-${RUN_ID}"
	if printf '%s' "$RCLI_OUT" | grep -qE '^[0-9]{9,}$'; then
		pass "TIME-in-Lua 后接写命令可用（5.0 按效果复制）" "返回 $(printf '%s' "$RCLI_OUT" | head -1)"
	else
		fail "TIME-in-Lua 写后置失败：AiRateLimiter 的滑动窗口脚本在 5.0.14 不可用" \
			"$(printf '%s' "$RCLI_OUT" | head -2 | tr '\n' ' ')"
	fi

	# 模拟 Lettuce 6.6 握手：CLIENT SETINFO 是 7.2+ 子命令，5.0.14 应拒绝；
	# 关键不是“拒绝”，而是拒绝后同一连接仍可继续执行命令（Lettuce 侧容错的前提）。
	out="$(printf 'CLIENT SETINFO LIB-NAME lettuce\nCLIENT SETINFO LIB-VER 6.6.0.RELEASE\nPING\n' \
		| docker exec -i "${NODES[0]}" redis-cli -p 6379 2>&1)"
	printf '%s\n' "$out" >>"$COMMANDS_LOG"
	n=$(printf '%s\n' "$out" | grep -ci 'Unknown subcommand' || true)
	if [ "$n" -ge 2 ]; then
		pass "5.0.14 拒绝 CLIENT SETINFO（$n 次 Unknown subcommand）" "Lettuce 6.6 握手须容错"
	else
		fail "CLIENT SETINFO 未按 5.0.14 预期被拒绝" "$(printf '%s' "$out" | tr '\n' ' ')"
	fi
	if printf '%s\n' "$out" | grep -qx 'PONG'; then
		pass "CLIENT SETINFO 被拒后同一连接仍可 PING"
	else
		fail "CLIENT SETINFO 被拒后连接不可用" "$(printf '%s' "$out" | tr '\n' ' ')"
	fi

	out="$(printf 'CLIENT SETNAME smoke\nPING\n' \
		| docker exec -i "${NODES[0]}" redis-cli -p 6379 2>&1)"
	if printf '%s\n' "$out" | grep -qx 'OK' && printf '%s\n' "$out" | grep -qx 'PONG'; then
		pass "CLIENT SETNAME（5.0 支持）可用作对照"
	else
		fail "CLIENT SETNAME 对照失败" "$(printf '%s' "$out" | tr '\n' ' ')"
	fi
}

# ── ⑥ 单主故障 ──────────────────────────────────────────────────────────────

check_failover() {
	section "⑥ 单主故障：立即证据 + 拓扑收敛后证据（分阶段，不做瞬时单值断言）"
	# I6：设计文档 §十 批次 0 ⑤ 要求的是产品 RedisAvailability.available 的取值；
	# 本网只有环境级 redis-cli 证据，产品链路未接。显式登记，避免事后把 summary 读成“⑤ 已验”。
	skip "设计文档批次 0 ⑤ / RedisAvailability.available 未在本网验证（需产品链路/单测后续测）" \
		"本网只给环境级 redis-cli 分阶段证据；设计文档 §十 批次 0 ⑤ 待补"
	local nodes_before master_line master_id master_ip master_port master_container master_running
	local replica_line replica_id obs
	local survivors=() mid line c ip
	local stage_a_ms stage_a_info dead_out nodes state slots_ok rline rflags rslots
	local converged elapsed final_masters nodes_after info_after
	local promoted_line promoted_slots found_key kslot promoted_ping_result
	local stage_rows="" row_now row_rel

	nodes_before=$(rcluster_nodes "${NODES[0]}")
	master_line=$(printf '%s\n' "$nodes_before" | awk '$3 ~ /master/ && $3 !~ /fail/ && NF >= 9 {print; exit}')
	if [ -z "$master_line" ]; then
		fail "故障注入前置失败：找不到带槽的主节点" "$nodes_before"
		return
	fi
	master_id=$(printf '%s' "$master_line" | awk '{print $1}')
	master_ip=$(node_addr_ip "$master_line")
	master_port=$(node_addr_port "$master_line")
	master_container=$(container_for_ip "$master_ip" || true)
	if [ -z "$master_container" ]; then
		fail "故障注入前置失败：主节点 IP 未映射到容器" "$master_ip"
		return
	fi

	replica_line=$(printf '%s\n' "$nodes_before" | awk -v m="$master_id" '$4 == m && $3 ~ /slave|replica/ {print; exit}')
	if [ -z "$replica_line" ]; then
		fail "故障注入前置失败：目标主节点没有从节点" "$master_id"
		return
	fi
	replica_id=$(printf '%s' "$replica_line" | awk '{print $1}')

	for mid in $(printf '%s\n' "$nodes_before" | awk '$3 ~ /master/ && $3 !~ /fail/ && NF >= 9 {print $1}'); do
		line=$(line_for_id "$nodes_before" "$mid")
		ip=$(node_addr_ip "$line")
		c=$(container_for_ip "$ip" || true)
		[ -z "$c" ] && continue
		[ "$c" = "$master_container" ] && continue
		survivors+=("$c")
	done
	if [ "${#survivors[@]}" -ne 2 ]; then
		fail "故障注入前置失败：存活主节点不是 2 个" "${survivors[*]:-none}"
		return
	fi
	obs=${survivors[0]}

	printf '故障目标：master=%s（%s:%s） 容器=%s；预期接替者=%s\n' \
		"$master_id" "$master_ip" "$master_port" "$master_container" "$replica_id"

	docker kill "$master_container" >/dev/null 2>&1 \
		|| { fail "docker kill 失败" "$master_container"; return; }
	stage_a_ms=$(now_ms)

	# 决定性事实：docker inspect 确认容器已停止。TCP 探测只是加强证据；
	# 容器仍在运行（或 inspect 输出异常）直接 FAIL，不再靠“非 PONG”推断故障注入成功。
	# docker kill 返回不等于 State 已落定：最多等 5s，仍为 true 才判失败。
	master_running=""
	for i in $(seq 1 25); do
		master_running="$(docker inspect -f '{{.State.Running}}' "$master_container" 2>/dev/null || true)"
		[ "$master_running" = "false" ] && break
		sleep 0.2
	done
	row_now=$(now_ms)
	row_rel=$((row_now - stage_a_ms))
	case "$master_running" in
	true)
		fail "docker kill 未生效：容器仍在运行（State.Running=true）" "$master_container +${row_rel}ms"
		stage_rows+="A-container-state"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_container"$'\t'"State.Running=true"$'\n'
		write_failover_stages "$stage_rows"
		return
		;;
	false)
		pass "docker inspect 确认容器已停止（State.Running=false）" "$master_container +${row_rel}ms"
		stage_rows+="A-container-state"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_container"$'\t'"State.Running=false"$'\n'
		;;
	*)
		fail "无法确认容器已停止（docker inspect 输出异常）" "State.Running=[$master_running] +${row_rel}ms"
		stage_rows+="A-container-state"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_container"$'\t'"unknown State.Running"$'\n'
		write_failover_stages "$stage_rows"
		return
		;;
	esac

	# A0：kill 后立即采带时间戳的集群视图（只记录、不断言；此时可能是 ok/fail 任意中间态）。
	rcli_capture "$obs" CLUSTER INFO
	stage_a_info=$RCLI_OUT
	row_now=$(now_ms)
	row_rel=$((row_now - stage_a_ms))
	printf '%s\n' "$stage_a_info" >"$ARTIFACT_DIR/failover-stage-a.txt"
	printf '%s\n' "$nodes_before" >>"$ARTIFACT_DIR/failover-stage-a.txt"
	stage_rows+="A-cluster-view"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$obs:CLUSTER INFO"$'\t'"state=$(info_field "$stage_a_info" cluster_state) slots_ok=$(info_field "$stage_a_info" cluster_slots_ok)"$'\n'

	# A1：存活主节点立即 PONG（先于慢探针，避免“立即”被 10s 超时拖成收敛后）。
	for c in "${survivors[@]}"; do
		row_now=$(now_ms)
		row_rel=$((row_now - stage_a_ms))
		if [ "$(rping "$c")" = "PONG" ]; then
			pass "kill 后立即 PING 存活主节点：PONG" "$c（+${row_rel}ms）"
			stage_rows+="A-survivor-ping"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$c"$'\t'"PONG"$'\n'
		else
			fail "存活主节点 PING 失败" "$c（+${row_rel}ms）"
			stage_rows+="A-survivor-ping"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$c"$'\t'"FAIL"$'\n'
		fi
	done

	# A2：死节点探针放最后（docker run 最坏 10s）。容器停止已由 A-container-state 决定；
	# 这里只区分“明确连接失败（加强证据）”与“10s 无任何响应（弱证据）”，不把空响应误报成“探测未执行”。
	_rcli_net_capture "$master_ip" "$master_port" PING
	dead_out=$RCLI_OUT
	row_now=$(now_ms)
	row_rel=$((row_now - stage_a_ms))
	if is_expected_connect_failure "$RCLI_RC" "$dead_out"; then
		pass "kill 后死节点探针：明确连接失败（非 PONG）" \
			"rc=$RCLI_RC +${row_rel}ms；refused 可能来自节点已释放 IP 被探针容器占用"
		stage_rows+="A-dead-probe"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_ip:$master_port"$'\t'"rc=$RCLI_RC $(printf '%s' "$dead_out" | head -1)"$'\n'
	elif [ "$RCLI_RC" = "124" ] && [ -z "$dead_out" ]; then
		pass "kill 后死节点：容器已停止但 10s 无响应（弱证据）" \
			"rc=124、无输出；TCP 包可能被静默丢弃"
		stage_rows+="A-dead-probe"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_ip:$master_port"$'\t'"rc=124 empty（弱证据）"$'\n'
	else
		fail "容器已停止，但探针输出既非预期连接失败也非 10s 空响应（探测本身异常）" \
			"rc=$RCLI_RC +${row_rel}ms $(printf '%s' "$dead_out" | head -1)"
		stage_rows+="A-dead-probe"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$master_ip:$master_port"$'\t'"unexpected rc=$RCLI_RC"$'\n'
	fi

	# B：轮询到拓扑收敛（state=ok、16384 槽可服务、原从节点已升主并持有槽）。
	converged=0
	elapsed=0
	local started
	started=$(date +%s)
	for i in $(seq 1 60); do
		state=$(info_field "$(rcluster_info "$obs")" cluster_state)
		slots_ok=$(info_field "$(rcluster_info "$obs")" cluster_slots_ok)
		nodes=$(rcluster_nodes "$obs")
		rline=$(line_for_id "$nodes" "$replica_id")
		rflags=$(printf '%s' "$rline" | awk '{print $3}')
		rslots=$(printf '%s' "$rline" | awk '{for (i = 9; i <= NF; i++) printf "%s ", $i}')
		if [ "$state" = "ok" ] && [ "$slots_ok" = "16384" ] \
			&& [[ "$rflags" == *master* ]] && [ -n "$rslots" ]; then
			converged=1
			break
		fi
		sleep 2
	done
	elapsed=$(( $(date +%s) - started ))

	if [ "$converged" = "1" ]; then
		pass "拓扑在 ${elapsed}s 内收敛（state=ok / slots_ok=16384 / 接替者升主并持槽）" \
			"接替者 $replica_id"
	else
		fail "拓扑 120s 内未收敛" "state=$state slots_ok=$slots_ok replica_flags=$rflags"
	fi
	row_now=$(now_ms)
	row_rel=$((row_now - stage_a_ms))
	stage_rows+="B-converged"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"cluster"$'\t'"state=${state:-unknown} slots_ok=${slots_ok:-unknown} elapsed=${elapsed}s"$'\n'

	nodes_after=$nodes
	info_after=$(rcluster_info "$obs")
	printf '%s\n' "$info_after" >"$ARTIFACT_DIR/failover-converged.txt"
	printf '%s\n' "$nodes_after" >>"$ARTIFACT_DIR/failover-converged.txt"
	printf '%s\n' "$nodes_after" >"$ARTIFACT_DIR/cluster-nodes-after.txt"

	assert_eq "$(info_field "$info_after" cluster_size)" "3" "收敛后仍有 3 个主节点（接替者补位）"
	assert_eq "$(info_field "$info_after" cluster_slots_ok)" "16384" "收敛后 16384 槽全部可服务"

	# C1：收敛后所有存活主节点 PONG。
	final_masters=$(printf '%s\n' "$nodes_after" | awk '$3 ~ /master/ && $3 !~ /fail/ && NF >= 9 {print $1}')
	for mid in $final_masters; do
		line=$(line_for_id "$nodes_after" "$mid")
		ip=$(node_addr_ip "$line")
		c=$(container_for_ip "$ip" || true)
		[ -z "$c" ] && continue
		row_now=$(now_ms)
		row_rel=$((row_now - stage_a_ms))
		if [ "$(rping "$c")" = "PONG" ]; then
			pass "收敛后 PING 存活主节点：PONG" "$c（+${row_rel}ms）"
			stage_rows+="B-master-ping"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$c"$'\t'"PONG"$'\n'
		else
			fail "收敛后主节点 PING 失败" "$c（+${row_rel}ms）"
			stage_rows+="B-master-ping"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"$c"$'\t'"FAIL"$'\n'
		fi
	done

	# C2：用一条全新连接 PING 接替者（证明网络可达与角色，不依赖已存在的 redis-cli 进程）。
	promoted_line=$(line_for_id "$nodes_after" "$replica_id")
	promoted_ip=$(node_addr_ip "$promoted_line")
	promoted_ping_result="not-run"
	if [ -n "$promoted_ip" ]; then
		_rcli_net_capture "$promoted_ip" 6379 PING
		promoted_ping_result=$(printf '%s' "$RCLI_OUT" | tr '\n\r\t' '   ')
		[ -z "$promoted_ping_result" ] && promoted_ping_result="(empty)"
		if [ "$RCLI_OUT" = "PONG" ]; then
			pass "收敛后新连接 PING 接替者：PONG" "$promoted_ip"
		else
			fail "收敛后新连接 PING 接替者失败" "$(printf '%s' "$RCLI_OUT" | head -1)"
		fi
	else
		fail "找不到接替者的 IP" "$promoted_line"
	fi
	row_now=$(now_ms)
	row_rel=$((row_now - stage_a_ms))
	stage_rows+="B-promoted-ping"$'\t'"$row_now"$'\t'"$row_rel"$'\t'"${promoted_ip:-unknown}"$'\t'"$promoted_ping_result"$'\n'

	# C3：往接替者负责的槽里写读（先在观测节点容器内算 120 个候选键的槽，挑一个落在其区间内的）。
	promoted_slots=$(printf '%s' "$promoted_line" | awk '{for (i = 9; i <= NF; i++) printf "%s ", $i}')
	if [ -n "$promoted_slots" ]; then
		docker exec "$obs" sh -c 'i=1; while [ $i -le 120 ]; do k="ai:run:fo-'"$RUN_ID"'-$i"; s=$(redis-cli -p 6379 cluster keyslot "$k"); echo "$s $k"; i=$((i+1)); done' \
			>"$ARTIFACT_DIR/slot-map.txt" 2>/dev/null || true
		found_key=$(pick_key_in_slots "$promoted_slots" "$ARTIFACT_DIR/slot-map.txt")
		if [ -n "$found_key" ]; then
			rcli_capture_c "$obs" CLUSTER KEYSLOT "$found_key"
			kslot=$RCLI_OUT
			rcli_capture_c "$obs" SET "$found_key" "v-failover"
			assert_eq "$RCLI_OUT" "OK" "收敛后写入接替者槽位成功" "key=$found_key slot=$kslot"
			rcli_capture_c "$obs" GET "$found_key"
			assert_eq "$RCLI_OUT" "v-failover" "收敛后读回一致" "key=$found_key"
		else
			fail "未能在接替者槽区间内生成探针键" "slots=$promoted_slots"
		fi
	else
		fail "接替者没有槽区间" "$promoted_line"
	fi

	# 分阶段证据表（原始结果，不做 available 单值断言）。
	write_failover_stages "$stage_rows"
}

# ── ⑦ 日志扫描 ──────────────────────────────────────────────────────────────

check_logs() {
	section "⑦ 日志扫描（commands.log 窄门禁；节点日志仅备查）"
	local c unexpected invalid_n cross_n setinfo_n

	for c in "${NODES[@]}"; do
		docker logs "$c" >"$ARTIFACT_DIR/redis-${c}.log" 2>&1 || true
	done
	# 节点日志只归档：故障切换期间 MASTER<->REPLICA、Marking node as failing 等是正常行，
	# 做白名单容易误伤；这里明确不设门禁（门禁只在 commands.log 的窄集合上）。
	skip "节点 docker logs 仅落盘备查（不设门禁）" "见 redis-*.log；服务端正常故障行不做白名单"

	# 命令日志里的错误只允许来自三条预期探针；其余一律视为检查自身/环境异常。
	unexpected=$(grep -E '^\(error\)|^ERR ' "$COMMANDS_LOG" 2>/dev/null \
		| grep -Ev 'Invalid stream ID|CROSSSLOT|Unknown subcommand' || true)
	if [ -z "$unexpected" ]; then
		pass "命令日志无白名单外错误"
	else
		fail "命令日志出现未预期错误" "$(printf '%s\n' "$unexpected" | head -3 | tr '\n' ' ')"
	fi

	invalid_n=$(grep -c 'Invalid stream ID' "$COMMANDS_LOG" || true)
	cross_n=$(grep -c 'CROSSSLOT' "$COMMANDS_LOG" || true)
	setinfo_n=$(grep -ci 'Unknown subcommand' "$COMMANDS_LOG" || true)

	if [ "$invalid_n" -ge 1 ]; then
		pass "日志命中 XRANGE 排他错误（预期探针确实执行）" "count=$invalid_n"
	else
		fail "日志未命中 XRANGE 排他错误（探针可能没跑）" "count=$invalid_n"
	fi
	if [ "$cross_n" -ge 1 ]; then
		pass "日志命中 CROSSSLOT（预期探针确实执行）" "count=$cross_n"
	else
		fail "日志未命中 CROSSSLOT（探针可能没跑）" "count=$cross_n"
	fi
	if [ "$setinfo_n" -ge 1 ]; then
		pass "日志命中 CLIENT SETINFO 拒绝（预期探针确实执行）" "count=$setinfo_n"
	else
		fail "日志未命中 CLIENT SETINFO 拒绝（探针可能没跑）" "count=$setinfo_n"
	fi

	{
		echo "expected probe hits (commands.log narrow gate):"
		echo "  XRANGE exclusive (Invalid stream ID): $invalid_n"
		echo "  CROSSSLOT: $cross_n"
		echo "  CLIENT SETINFO (Unknown subcommand): $setinfo_n"
		echo
		echo "node docker logs: archived only, NO gate (MASTER<->REPLICA / failing lines are normal)"
		echo "server log scan (CLIENT SETINFO / TIME / XRANGE / CROSSSLOT):"
		grep -nE 'CLIENT SETINFO|Unknown subcommand|Invalid stream ID|CROSSSLOT|TIME' \
			"$ARTIFACT_DIR"/redis-*.log 2>/dev/null || echo "  (no matches)"
	} >"$ARTIFACT_DIR/log-scan.txt"

	if [ -n "${SMOKE_BACKEND_LOG:-}" ]; then
		if [ -f "$SMOKE_BACKEND_LOG" ]; then
			grep -nE 'Invalid stream ID|CROSSSLOT|CLIENT SETINFO|Unknown subcommand|XRANGE' \
				"$SMOKE_BACKEND_LOG" >"$ARTIFACT_DIR/backend-log-scan.txt" 2>/dev/null || true
			if grep -q 'Invalid stream ID' "$SMOKE_BACKEND_LOG"; then
				fail "后端日志出现 XRANGE 排他错误（产品链路已实际触发）" "$SMOKE_BACKEND_LOG"
			else
				pass "后端日志未出现 XRANGE 排他错误" "$SMOKE_BACKEND_LOG"
			fi
			if grep -q 'CROSSSLOT' "$SMOKE_BACKEND_LOG"; then
				fail "后端日志出现 CROSSSLOT" "$SMOKE_BACKEND_LOG"
			else
				pass "后端日志未出现 CROSSSLOT" "$SMOKE_BACKEND_LOG"
			fi
		else
			skip "SMOKE_BACKEND_LOG 指向的文件不存在" "$SMOKE_BACKEND_LOG"
		fi
	else
		skip "未提供 SMOKE_BACKEND_LOG（本网不启动后端；修复验收时传入后端日志再扫）"
	fi
}

# ── 入口 ────────────────────────────────────────────────────────────────────

main() {
	local mode="real"
	case "${1:-}" in
	"") ;;
	--static)
		mode="static"
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		printf '未知参数: %s\n' "$1" >&2
		usage >&2
		exit 2
		;;
	esac

	mkdir -p "$ARTIFACT_DIR" || exit 2
	COMMANDS_LOG="$ARTIFACT_DIR/commands.log"
	: >"$COMMANDS_LOG"

	if [ "$mode" = "static" ]; then
		MODE_LABEL="static-only（未连接 Redis）"
		REAL_MODE=0
	else
		MODE_LABEL="real-cluster（$EXPECTED_VERSION，3 主 3 从）"
		REAL_MODE=1
		# 失败时（镜像不对/集群未起来）如实写“未取得证据”，不要沿用静态模式的文案。
		TOPOLOGY_LABEL="未就绪（集群启动/收敛失败）"
		REAL_LABEL="否（未取得真实集群证据）"
	fi

	printf 'Redis %s 集群冒烟网\n' "$EXPECTED_VERSION"
	printf '模式: %s\n' "$MODE_LABEL"
	printf '镜像: %s\n' "$REDIS_IMAGE"
	printf '证据目录: %s\n' "$ARTIFACT_DIR"

	check_product_xrange_contract
	check_file_hygiene

	if [ "$mode" = "real" ]; then
		start_cluster
		check_xrange
		check_crossslot
		check_del_semantics
		check_pubsub
		check_time_and_setinfo
		check_failover
		check_logs
	fi

	write_summary
	printf '\n结果: PASS %s / FAIL %s / KNOWN-LIMIT %s / SKIP %s\n' \
		"$PASS_N" "$FAIL_N" "$KNOWN_N" "$SKIP_N"

	if [ "$FAIL_N" -gt 0 ]; then
		exit 1
	fi
	exit 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
	main "$@"
fi
