"""Command line entry point.

Configuration comes from environment variables only (see .env.example).
CLI flags only choose the action mode (dry-run/execute, keep leftovers).
"""

from __future__ import annotations

import argparse
import sys
from typing import Dict, List, Optional

from . import __version__
from . import cleanup as cleanup_mod
from . import generate as generate_mod
from . import manifest as M
from . import measure as measure_mod
from . import preflight as preflight_mod
from . import report
from . import scenarios as scenarios_mod
from . import verify as verify_mod
from .envcfg import Config, ConfigError, compare_git, load

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_CONFIG = 2
EXIT_UNSUPPORTED = 3


def _print_config_errors(cfg: Config) -> None:
    if not cfg.errors:
        return
    print("配置错误：", file=sys.stderr)
    for item in cfg.errors:
        print("  - " + item, file=sys.stderr)
    print("提示：复制 .env.example 为 .env 并导出，或直接设置环境变量。", file=sys.stderr)


def _print_checks(title: str, result: Dict[str, object]) -> None:
    print("== %s ==" % title)
    for item in result.get("checks", []):
        print("[%s] %s: %s" % (str(item.get("status", "")).upper(),
                               item.get("check"), item.get("detail")))
    counts = result.get("counts", {})
    print("summary: pass=%s fail=%s unknown=%s info=%s"
          % (counts.get("pass", 0), counts.get("fail", 0),
             counts.get("unknown", 0), counts.get("info", 0)))


def _print_stats(result: Dict[str, object]) -> None:
    print("== measurement ==")
    for item in result.get("stats", []):
        print("%-26s %-14s n=%-4s mean=%8.1fms p50=%8.1fms p95=%8.1fms p99=%8.1fms max=%8.1fms %s"
              % (item.get("scenario"), item.get("operation"), item.get("count"),
                 float(item.get("mean") or 0.0), float(item.get("p50") or 0.0),
                 float(item.get("p95") or 0.0), float(item.get("p99") or 0.0),
                 float(item.get("max") or 0.0), item.get("http_status")))
    print("measured requests=%s transport failures=%s http failures=%s allowed_http=%s"
          % (result.get("measured_requests"), result.get("transport_failures"),
             result.get("http_failures"), result.get("allowed_http_responses")))
    budget = result.get("budget") or {}
    if budget:
        print("budget: total=%ss scenario=%ss elapsed=%.1fs exhausted=%s"
              % (budget.get("total_seconds"), budget.get("scenario_seconds"),
                 float(budget.get("elapsed_seconds") or 0.0), budget.get("exhausted")))
    for item in result.get("scenarios", []):
        if item.get("status") == "budget_exhausted" or (
                item.get("status") == "skipped" and item.get("reason") == "budget_exhausted"):
            print("budget %-26s status=%-17s skipped=%s"
                  % (item.get("scenario"), item.get("status"),
                     len(item.get("skipped") or [])))


def _print_verify(result: Dict[str, object]) -> None:
    print("== verify ==")
    for item in result.get("checks", []):
        if item.get("status") in ("fail", "unknown"):
            print("[%s] %s/%s %s: %s" % (str(item.get("status")).upper(),
                                         item.get("scenario"), item.get("copy"),
                                         item.get("check"), item.get("detail")))
    counts = result.get("counts", {})
    print("summary: pass=%s fail=%s unknown=%s info=%s skip=%s"
          % (counts.get("pass", 0), counts.get("fail", 0), counts.get("unknown", 0),
             counts.get("info", 0), counts.get("skip", 0)))


def _print_cleanup(result: Dict[str, object]) -> None:
    print("== cleanup ==")
    print("executed=%s complete=%s" % (result.get("executed"), result.get("complete")))
    plan = result.get("plan") or {}
    print("exact keys=%s present=%s backgrounds=%s"
          % (plan.get("exact_keys"), plan.get("exact_present"), len(plan.get("background") or [])))
    if plan.get("unknown_background"):
        print("unknown background buckets（无 planned count，不伪报干净）：%s"
              % ",".join(plan.get("unknown_background") or []))
    if not result.get("executed"):
        print("dry-run：未删除任何键。确认清单后使用 cleanup --execute。")
    remaining = result.get("remaining") or {}
    if remaining:
        print("remaining exact=%s scanned=%s background=%s"
              % (len(remaining.get("exact") or []), len(remaining.get("scanned") or []),
                 len(remaining.get("background") or [])))


def _load_manifest_or_fail(cfg: Config) -> Optional[Dict[str, object]]:
    try:
        manifest = M.load_manifest(cfg)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return None
    if manifest is None:
        print("没有 manifest：先执行 generate（或检查 PERF_OUTPUT_DIR / PERF_NAMESPACE）", file=sys.stderr)
        return None
    return manifest


def _selection(cfg: Config):
    try:
        return scenarios_mod.resolve_selection(cfg)
    except (ValueError, ConfigError) as exc:
        print(str(exc), file=sys.stderr)
        return None


def cmd_scenarios(cfg: Config) -> int:
    matrix = scenarios_mod.build_matrix(cfg)
    selection = _selection(cfg)
    if selection is None:
        return EXIT_CONFIG
    selected, blocked = selection
    blocked_names = {item["scenario"]: item["reason"] for item in blocked}
    print("%-28s %-10s %-8s %-11s %s" % ("scenario", "scale", "delete", "selected", "description"))
    for name in sorted(matrix):
        item = matrix[name]
        print("%-28s %-10s %-8s %-11s %s"
              % (name, item["scale"], "yes" if item["destructive"] else "no",
                 "yes" if name in selected else ("blocked" if name in blocked_names else "no"),
                 item["description"]))
        if name in blocked_names:
            print("    blocked: %s" % blocked_names[name])
    print("profile=%s selected=%d blocked=%d" % (cfg.profile, len(selected), len(blocked)))
    return EXIT_OK


def cmd_doctor(cfg: Config, args) -> int:
    M.resolve_namespace(cfg)
    result = preflight_mod.doctor(cfg)
    paths = M.workspace(cfg)
    M.atomic_write_json(paths["doctor"], result)
    _print_checks("doctor", result)
    print("namespace=%s output=%s" % (cfg.namespace, paths["root"]))
    counts = result.get("counts", {})
    return EXIT_OK if counts.get("fail", 0) == 0 and counts.get("unknown", 0) == 0 else EXIT_CONFIG


def cmd_preflight(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    M.resolve_namespace(cfg)
    try:
        result = preflight_mod.preflight(cfg)
    except ConfigError as exc:
        _print_config_errors(Config(errors=exc.errors))
        return EXIT_CONFIG
    paths = M.workspace(cfg)
    M.atomic_write_json(paths["preflight"], result)
    _print_checks("preflight", result)
    print("namespace=%s output=%s" % (cfg.namespace, paths["root"]))
    counts = result.get("counts", {})
    return EXIT_OK if counts.get("fail", 0) == 0 and counts.get("unknown", 0) == 0 else EXIT_CONFIG


def cmd_generate(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    selection = _selection(cfg)
    if selection is None:
        return EXIT_CONFIG
    selected, blocked = selection
    if not selected:
        print("没有可执行场景（全部被阻止或未选择）", file=sys.stderr)
        return EXIT_UNSUPPORTED
    try:
        cfg.require_write_safety()
    except ConfigError as exc:
        _print_config_errors(Config(errors=exc.errors))
        return EXIT_CONFIG
    M.resolve_namespace(cfg)
    paths = M.workspace(cfg)
    try:
        manifest = M.load_manifest(cfg) or M.new_manifest(cfg)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return EXIT_CONFIG
    try:
        summary = generate_mod.generate(cfg, manifest, selected)
    except Exception as exc:
        print("生成失败：%s" % exc, file=sys.stderr)
        return EXIT_FAIL
    M.save_manifest(cfg, manifest)
    print("== generate ==")
    print("namespace=%s mode=%s app_reachable=%s"
          % (cfg.namespace, cfg.mode, summary.get("app_reachable")))
    for name, item in (summary.get("background") or {}).items():
        print("background %-8s written=%s count=%s done=%s"
              % (name, item.get("written"), item.get("count"), item.get("done")))
    for name, item in (summary.get("scenarios") or {}).items():
        print("scenario %-28s status=%-18s copies=%s %s"
              % (name, item.get("status"), item.get("copies", "-"), item.get("reason", "")))
    for item in blocked:
        print("blocked  %-28s %s" % (item.get("scenario"), item.get("reason")))
    print("manifest=%s" % paths["manifest"])
    statuses = [item.get("status") for item in (summary.get("scenarios") or {}).values()]
    if "failed" in statuses:
        return EXIT_FAIL
    if "unsupported" in statuses:
        return EXIT_UNSUPPORTED
    return EXIT_OK


def cmd_measure(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    selection = _selection(cfg)
    if selection is None:
        return EXIT_CONFIG
    selected, _blocked = selection
    M.resolve_namespace(cfg)
    manifest = _load_manifest_or_fail(cfg)
    if manifest is None:
        return EXIT_CONFIG
    try:
        cfg.require_write_safety()
    except ConfigError as exc:
        _print_config_errors(Config(errors=exc.errors))
        return EXIT_CONFIG
    result = measure_mod.measure(cfg, manifest, selected)
    paths = M.workspace(cfg)
    report.write_all(cfg, paths, result, None, None, compare_git(preflight_mod._repo_root()))
    _print_stats(result)
    print("results=%s" % paths["results"])
    if result.get("budget_exhausted_scenarios", 0) > 0 or result.get("skipped_scenarios", 0) > 0:
        print("measure 预算耗尽：部分场景未跑完或未开始，结果不构成性能通过"
              "（见 results.json 的 budget / scenarios[].skipped）", file=sys.stderr)
        return EXIT_FAIL
    if result.get("measured_requests", 0) == 0:
        return EXIT_UNSUPPORTED
    return EXIT_FAIL if result.get("failed_requests", 0) > 0 else EXIT_OK


def _measure_from_results(cfg: Config) -> Optional[Dict[str, object]]:
    paths = M.workspace(cfg)
    data = M.read_json(paths["results"])
    if not isinstance(data, dict):
        return None
    measure = data.get("measure")
    return measure if isinstance(measure, dict) else None


def cmd_verify(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    selection = _selection(cfg)
    if selection is None:
        return EXIT_CONFIG
    selected, _blocked = selection
    M.resolve_namespace(cfg)
    manifest = _load_manifest_or_fail(cfg)
    if manifest is None:
        return EXIT_CONFIG
    measure_result = _measure_from_results(cfg)
    if measure_result is None:
        print("没有测量结果：先执行 measure（或检查 PERF_OUTPUT_DIR）", file=sys.stderr)
        return EXIT_CONFIG
    result = verify_mod.verify(cfg, manifest, measure_result, selected)
    paths = M.workspace(cfg)
    report.write_all(cfg, paths, measure_result, result, None, compare_git(preflight_mod._repo_root()))
    _print_verify(result)
    counts = result.get("counts", {})
    if counts.get("fail", 0) > 0:
        return EXIT_FAIL
    if counts.get("unknown", 0) > 0:
        return EXIT_CONFIG
    if counts.get("pass", 0) == 0 and counts.get("info", 0) == 0 and counts.get("skip", 0) > 0:
        return EXIT_UNSUPPORTED
    return EXIT_OK


def cmd_cleanup(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    M.resolve_namespace(cfg)
    try:
        manifest = M.load_manifest(cfg)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return EXIT_CONFIG
    if manifest is None:
        print("警告：没有 manifest，仅能清理本命名空间的背景键；会话级键无法推导", file=sys.stderr)
        manifest = M.new_manifest(cfg)
    execute = bool(getattr(args, "execute", False))
    try:
        result = cleanup_mod.cleanup(cfg, manifest, execute)
    except ConfigError as exc:
        _print_config_errors(Config(errors=exc.errors))
        return EXIT_CONFIG
    paths = M.workspace(cfg)
    report.write_cleanup_only(paths, result)
    _print_cleanup(result)
    print("cleanup_json=%s" % paths["cleanup_json"])
    if execute and result.get("complete") is not True:
        return EXIT_FAIL
    return EXIT_OK


def cmd_all(cfg: Config, args) -> int:
    if cfg.errors:
        _print_config_errors(cfg)
        return EXIT_CONFIG
    selection = _selection(cfg)
    if selection is None:
        return EXIT_CONFIG
    selected, blocked = selection
    if not selected:
        print("没有可执行场景", file=sys.stderr)
        return EXIT_UNSUPPORTED
    M.resolve_namespace(cfg)
    pre = preflight_mod.preflight(cfg)
    paths = M.workspace(cfg)
    M.atomic_write_json(paths["preflight"], pre)
    _print_checks("preflight", pre)
    if pre.get("counts", {}).get("fail", 0) > 0 or pre.get("counts", {}).get("unknown", 0) > 0:
        return EXIT_CONFIG

    try:
        manifest = M.load_manifest(cfg)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return EXIT_CONFIG
    if manifest is None:
        manifest = M.new_manifest(cfg)
    try:
        generate_summary = generate_mod.generate(cfg, manifest, selected)
    except Exception as exc:
        print("生成失败：%s" % exc, file=sys.stderr)
        return EXIT_FAIL
    M.save_manifest(cfg, manifest)
    print("== generate ==")
    for name, item in (generate_summary.get("scenarios") or {}).items():
        print("scenario %-28s status=%-18s copies=%s"
              % (name, item.get("status"), item.get("copies", "-")))

    measure_result = measure_mod.measure(cfg, manifest, selected)
    _print_stats(measure_result)
    verify_result = verify_mod.verify(cfg, manifest, measure_result, selected)
    _print_verify(verify_result)

    cleanup_result = None
    if not getattr(args, "keep", False):
        execute = not getattr(args, "cleanup_dry_run", False)
        cleanup_result = cleanup_mod.cleanup(cfg, manifest, execute)
        _print_cleanup(cleanup_result)

    report.write_all(cfg, paths, measure_result, verify_result, cleanup_result,
                     compare_git(preflight_mod._repo_root()),
                     extra={"generate": generate_summary, "blocked": blocked})
    print("results=%s" % paths["results"])

    verify_counts = verify_result.get("counts", {})
    if verify_counts.get("fail", 0) > 0:
        return EXIT_FAIL
    if measure_result.get("budget_exhausted_scenarios", 0) > 0 or measure_result.get("skipped_scenarios", 0) > 0:
        print("measure 预算耗尽：部分场景未跑完或未开始，结果不构成性能通过", file=sys.stderr)
        return EXIT_FAIL
    if measure_result.get("failed_requests", 0) > 0:
        return EXIT_FAIL
    if measure_result.get("measured_requests", 0) == 0:
        return EXIT_UNSUPPORTED
    if cleanup_result is not None and cleanup_result.get("executed") and cleanup_result.get("complete") is not True:
        return EXIT_FAIL
    if verify_counts.get("unknown", 0) > 0:
        return EXIT_CONFIG
    statuses = [item.get("status") for item in (generate_summary.get("scenarios") or {}).values()]
    if "failed" in statuses:
        return EXIT_FAIL
    if "unsupported" in statuses:
        return EXIT_UNSUPPORTED
    return EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m perf_redis",
        description="AI 会话/Redis 性能验证工具（只造数据 + 调真实 HTTP API）",
    )
    parser.add_argument("--version", action="version", version=__version__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("doctor", help="只读环境/拓扑/应用身份检查")
    sub.add_parser("preflight", help="深度预检（含本命名空间写探测）")
    sub.add_parser("scenarios", help="打印场景矩阵与当前选择")
    sub.add_parser("generate", help="按场景生成本命名空间的数据")
    sub.add_parser("measure", help="预热 + 多轮 + 并发测量真实 HTTP API")
    sub.add_parser("verify", help="按源码语义核对删除与有意保留")
    cleanup_parser = sub.add_parser("cleanup", help="清理本命名空间（默认 dry-run）")
    group = cleanup_parser.add_mutually_exclusive_group()
    group.add_argument("--execute", action="store_true", help="真正删除（默认 dry-run）")
    group.add_argument("--dry-run", action="store_true", help="只打印清单（默认）")
    all_parser = sub.add_parser("all", help="preflight→generate→measure→verify→cleanup")
    all_parser.add_argument("--keep", action="store_true", help="保留生成数据（跳过 cleanup）")
    all_parser.add_argument("--cleanup-dry-run", action="store_true", help="cleanup 只打印清单")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    parser = build_parser()
    args = parser.parse_args(argv)
    cfg = load()
    try:
        if args.command == "scenarios":
            if cfg.errors:
                _print_config_errors(cfg)
                return EXIT_CONFIG
            return cmd_scenarios(cfg)
        if args.command == "doctor":
            return cmd_doctor(cfg, args)
        if args.command == "preflight":
            return cmd_preflight(cfg, args)
        if args.command == "generate":
            return cmd_generate(cfg, args)
        if args.command == "measure":
            return cmd_measure(cfg, args)
        if args.command == "verify":
            return cmd_verify(cfg, args)
        if args.command == "cleanup":
            return cmd_cleanup(cfg, args)
        if args.command == "all":
            return cmd_all(cfg, args)
        parser.print_help()
        return EXIT_CONFIG
    except KeyboardInterrupt:
        print("中断：已生成的键都带 TTL；请执行 cleanup 清理本命名空间", file=sys.stderr)
        return 130
    except ConfigError as exc:
        _print_config_errors(Config(errors=exc.errors))
        return EXIT_CONFIG
    except Exception as exc:
        print("命令失败：%s" % exc, file=sys.stderr)
        return EXIT_FAIL
