package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.web.dto.ModelView;
import com.example.configmgr.ai.web.dto.ProviderView;
import com.example.configmgr.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置的<b>只读</b>端点（模型配置专项批①；设计稿 §3.5 的第一行）。
 *
 * <p>
 * 批① 只给 {@code GET /api/ai/providers}：provider + 模型 allowlist 的现状，供设置页与排障使用。
 * 写端点（POST/PUT/DELETE、{@code /test}、{@code /models/fetch}）在批②；这里刻意<b>不</b>留
 * "占位 501"，免得前端误以为已经能用。
 *
 * <p>
 * 口径：业务信封 {@link ApiResponse}（与 AI 端点的裸对象族并存，见 {@code api/http.ts} 的
 * {@code success} 判别）；响应体<b>永不含</b> key 的明文或密文（{@link ProviderView}）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    private final AiConfigService configService;

    public AiConfigController(AiConfigService configService) {
        this.configService = configService;
    }

    /** provider 列表 + 每个 provider 的模型 allowlist（只读）。 */
    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> providers() {
        List<ProviderView> out = new ArrayList<>();
        for (AiProvider provider : this.configService.listProviders()) {
            out.add(toView(provider, this.configService.modelsOf(provider.getId())));
        }
        return ApiResponse.ok(out);
    }

    private static ProviderView toView(AiProvider provider, List<AiProviderModel> models) {
        return new ProviderView(provider.getId(), provider.getCode(), provider.getDisplayName(),
                provider.getProtocol(), provider.getBaseUrl(), provider.getCompletionsPath(),
                provider.getModelsPath(), provider.isKeyDeclared(), provider.getKeyLast4(), provider.getKeyRef(),
                provider.getSource(), provider.isEnabled(),
                models.stream().map(AiConfigController::toModelView).toList());
    }

    private static ModelView toModelView(AiProviderModel model) {
        return new ModelView(model.getId(), model.getModelId(),
                model.getDisplayName() == null ? model.getModelId() : model.getDisplayName(),
                model.isSupportsToolCalling(), Boolean.TRUE.equals(model.getSupportsVision()),
                Boolean.TRUE.equals(model.getSupportsReasoning()), model.getMaxContext(), model.getMaxOutput(),
                model.isEnabled(), model.isDefault(), model.getSortOrder());
    }
}
