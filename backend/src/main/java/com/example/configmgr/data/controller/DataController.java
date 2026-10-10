package com.example.configmgr.data.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.data.service.QueryCondition;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final ConfigDataRowRepository dataRowRepository;
    private final DefinitionService definitionService;
    private final ConfigDataService configDataService;

    /** scopeType 未传时按配置定义的层级推导（而非写死 GLOBAL） */
    private String effectiveScopeType(String defCode, String scopeType) {
        if (scopeType != null && !scopeType.isBlank()) {
            return scopeType;
        }
        try {
            return definitionService.findByCode(defCode).getLevel().name();
        } catch (Exception e) {
            return "GLOBAL";
        }
    }

    /**
     * 数据列表（分页）。
     *
     * <p>S4.4b 遗留 3 修复：新增 {@code conditions} 入参（{@link QueryCondition} 的 JSON），
     * 与 {@code /count}、导出作业同构 —— 无条件时走 DB 分页（scopeType + scopeKey 收窄），
     * 有条件时按同口径逐行求值后再分页，故前端不再需要"只筛当前页"的镜像求值器。
     *
     * <p><b>分页缺省值 20（2026-10-10 数字规格清点裁决⑥：前后端统一为 20）</b>：本端点缺省页大小
     * 原为 50，与数据浏览器（{@code frontend/src/views/DataBrowserView.vue} 的
     * {@code const size = ref(20)}）不一致 —— 同一资源两套默认（前端总显式传参故未爆，但口径已分叉）。
     * 统一取 <b>20</b>，依据业界依据 §8「分页大小主流默认」：GitHub 默认 30 / 上限 100、
     * Stripe 默认 10 / 范围 1–100；内网小数据量场景推荐"默认 20，前端提供 20/50/100 切换，
     * 后端硬上限 100"。
     *
     * <p><b>口径澄清：「后端硬上限 100」属业界推荐值，本端点尚未强制（2026-10-10 红队 MINOR-1 如实口径收口）</b>：
     * 上方依据段的 100 是推荐口径、<b>不等于本端点已设上限</b> —— 本端点对 {@code size} <b>无 clamp、无校验</b>，
     * {@code size} 直通 {@code PageRequest.of}（见下方实现），传任意大值（如 {@code size=999999}）不会被拒、
     * 也不会被截断。强制实现登记在案待收口，候选 = {@code Math.min(size, 100)}（静默夹取）或
     * {@code @Max(100)} 校验 + 400（显式拒绝）；登记见 {@code docs/M2-排期计划.md}「等待治理排查登记注记」节。
     * 本段只澄清措辞，不改行为。
     *
     * <p><b>本值是变更值（50 → 20）</b>：影响面仅"不传 {@code size} 的直连调用"（curl / 集成测试 /
     * 将来的第三方消费方）；前端数据浏览器与 E2E 脚本均显式传参，行为面零回归。
     * <b>改本值须连带复核</b>：{@code frontend/src/views/DataBrowserView.vue} 的
     * {@code const size = ref(20)} 与同页 {@code :page-sizes} 选项表（20/50/100）—— 三者须同口径，
     * 否则同一资源再次分叉。
     *
     * <p>同批登记（不在本步改动面）：作业问题列表 {@code JobController} 缺省 50 ↔
     * {@code stores/task.ts} 缺省 50（同源一致）、任务列表 {@code TaskController} 缺省 10 ↔
     * {@code stores/task.ts} 缺省 10（同源一致）、{@code views/ImportWizardView.vue} 拉问题列表
     * {@code getIssues(jobId, 0, 200)}（与 {@code app.ai.tool-result.max-rows} 的 200
     * <b>同值不同义</b>）。是否把 {@code JobController} 的 50 也统一为 20 属新决策，
     * 2026-10-10 已登记为待裁决项，本步不动。
     */
    @GetMapping("/{defCode}")
    public ApiResponse<Page<ConfigDataRow>> list(@PathVariable String defCode,
                                                   @RequestParam(required = false) String scopeType,
                                                   @RequestParam(required = false) String scopeKey,
                                                   @RequestParam(required = false) String conditions,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        QueryCondition cond = configDataService.parseCondition(conditions);
        if (cond != null) {
            return ApiResponse.ok(configDataService.findPagedFiltered(defCode, scopeKey, cond, page, size));
        }
        return ApiResponse.ok(dataRowRepository.findRowsInScopePaged(
                defCode, effectiveScopeType(defCode, scopeType), scopeKey,
                org.springframework.data.domain.PageRequest.of(page, size)));
    }

    /**
     * 预估行数：支持带查询条件（conditions 为 QueryCondition 的 JSON），
     * 与导出作业的过滤口径完全一致（并与列表端点的 {@code conditions} 同源，见
     * {@link ConfigDataService#findPagedFiltered}）。
     */
    @GetMapping("/{defCode}/count")
    public ApiResponse<Map<String, Long>> count(@PathVariable String defCode,
                                                  @RequestParam(required = false) String scopeType,
                                                  @RequestParam(required = false) String scopeKey,
                                                  @RequestParam(required = false) String conditions) {
        QueryCondition cond = configDataService.parseCondition(conditions);

        long count = cond != null
                ? configDataService.countFiltered(defCode, scopeKey, cond)
                : dataRowRepository.countByScope(defCode, effectiveScopeType(defCode, scopeType), scopeKey);
        return ApiResponse.ok(Map.of("count", count));
    }
}
