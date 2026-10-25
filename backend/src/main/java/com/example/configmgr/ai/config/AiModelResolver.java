package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProviderModel;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Optional;

/**
 * 模型解析的唯一口径（模型配置专项 §3.4；测试矩阵 B12）。
 *
 * <h2>优先级（高 → 低）</h2>
 * <ol>
 * <li>{@code explicitModelId}：<b>请求侧显式指定</b> —— 批③ 的请求体 {@code modelId}（与前端会话级
 * sessionStorage 的选择，二者在到达后端前已合并成这一个值）；</li>
 * <li>{@code ai_model.is_default = true}：该 provider 的默认模型标记；</li>
 * <li>该 provider 第一个 {@code enabled} 模型（批① 的既有口径，保留：没有标记时的"有什么用什么"）；</li>
 * <li>{@code settingDefaultModelId}：调用方传入的全局默认（默认目标路径<b>不</b>再走这一级：
 * {@code default.modelId} 必须与 {@code default.providerId} 成对精确命中，见
 * {@code AiConfigService#resolveDefault}）；</li>
 * <li>{@code envDefaultModelId}：调用方传入的环境兜底（仅 env 合成行使用）。</li>
 * </ol>
 *
 * <p>
 * 为什么做成无状态的静态解析：provider 内的"默认模型"顺序要被默认目标解析、{@code /health} 的
 * {@code defaultModelId} 与批③ 的按请求解析共用，放进任一 service 都会让另外两处绕路或复制。
 * 纯函数也意味着"交换两级"这种缺陷能被单测直接钉住（B12 的证红方式）。
 */
public final class AiModelResolver {

    private AiModelResolver() {
    }

    /**
     * @param explicitModelId 请求侧显式模型（null/空白 = 没指定）
     * @param providerModels 该 provider 的模型行（顺序即库内顺序：sort_order, id）
     * @param settingDefaultModelId {@code ai_setting['default.modelId']}（null/空白 = 未设置）
     * @param envDefaultModelId env {@code AI_MODEL}（最终兜底，可为 null）
     * @return 解析出的模型名；全部为空时返回 null（调用方决定怎么报错）
     */
    public static String resolve(String explicitModelId, Collection<AiProviderModel> providerModels,
            String settingDefaultModelId, String envDefaultModelId) {
        if (StringUtils.hasText(explicitModelId)) {
            return explicitModelId.trim();
        }
        Collection<AiProviderModel> models = providerModels == null ? java.util.List.of() : providerModels;
        Optional<AiProviderModel> flagged = models.stream()
            .filter(AiProviderModel::isEnabled)
            .filter(AiProviderModel::isDefault)
            .findFirst();
        if (flagged.isPresent()) {
            return flagged.get().getModelId();
        }
        Optional<AiProviderModel> firstEnabled = models.stream().filter(AiProviderModel::isEnabled).findFirst();
        if (firstEnabled.isPresent()) {
            return firstEnabled.get().getModelId();
        }
        if (StringUtils.hasText(settingDefaultModelId)) {
            return settingDefaultModelId.trim();
        }
        return StringUtils.hasText(envDefaultModelId) ? envDefaultModelId.trim() : null;
    }
}
