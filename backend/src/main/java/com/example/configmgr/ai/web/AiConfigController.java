package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigException;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiConfigWriter;
import com.example.configmgr.ai.config.AiConnectionProbe;
import com.example.configmgr.ai.config.AiEndpointGuard;
import com.example.configmgr.ai.config.AiKeyCipher;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.web.dto.AvailableModelView;
import com.example.configmgr.ai.web.dto.DefaultsView;
import com.example.configmgr.ai.web.dto.FetchModelsView;
import com.example.configmgr.ai.web.dto.ModelView;
import com.example.configmgr.ai.web.dto.ProbeView;
import com.example.configmgr.ai.web.dto.ProviderView;
import com.example.configmgr.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置端点（模型配置专项 §3.5）：批① 的只读面 + 批② 的写路径、{@code /test}、{@code /models/fetch}、
 * 模型 allowlist、全局默认参数。
 *
 * <p>
 * 口径：
 * <ul>
 * <li>业务信封 {@link ApiResponse}（与 AI 端点的裸对象族并存，见 {@code api/http.ts} 的
 * {@code success} 判别）；</li>
 * <li>响应体<b>永不含</b> key 的明文/密文/指纹（{@link ProviderView} 只有四件套）；</li>
 * <li>错误只给稳定 {@code code} + 中文兜底：HTTP 状态由 {@link AiConfigException#getStatus()} 统一给；
 * 前端按 code 查文案（i18n 口径，F3 的用例锁"不显示后端中文"）；</li>
 * <li>{@code /test} 与 {@code /models/fetch} 的失败是<b>观测结果</b>（200 + {@code ok=false} + code），
 * 不是请求错误 —— 见 {@link ProbeView} 的类注。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    private final AiConfigService configService;

    private final AiConfigWriter writer;

    private final AiConnectionProbe probe;

    private final AiModelCatalog catalog;

    public AiConfigController(AiConfigService configService, AiConfigWriter writer, AiConnectionProbe probe,
            AiModelCatalog catalog) {
        this.configService = configService;
        this.writer = writer;
        this.probe = probe;
        this.catalog = catalog;
    }

    // ── provider 读 ──────────────────────────────────────────────────────────

    /** provider 列表 + 每个 provider 的模型 allowlist（只读）。 */
    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> providers() {
        List<ProviderView> out = new ArrayList<>();
        for (AiProvider provider : this.configService.listProviders()) {
            out.add(toView(provider, this.configService.modelsOf(provider.getId())));
        }
        return ApiResponse.ok(out);
    }

    // ── provider 写 ──────────────────────────────────────────────────────────

    /**
     * 面板可选的模型（跨 provider；设计稿 §3.5 的「对话面板用」端点）。
     *
     * <p>与 {@code GET /providers} 的分工：这个只回答"现在能选什么"，不含任何配置面字段
     * （baseUrl / keyRef / 路径）；可用性三条判据在后端一次判完（见
     * {@link AiConfigService#availableModels()}），面板不做二次过滤。
     */
    @GetMapping("/models")
    public ApiResponse<List<AvailableModelView>> models() {
        List<AvailableModelView> out = new ArrayList<>();
        for (AiConfigService.AvailableModel row : this.configService.availableModels()) {
            out.add(new AvailableModelView(row.providerId(), row.providerCode(), row.providerName(), row.modelId(),
                    row.displayName(), row.supportsToolCalling(), row.supportsVision(), row.supportsReasoning(),
                    row.maxContext(), row.maxOutput(), row.isDefault()));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping("/providers")
    public ResponseEntity<ApiResponse<ProviderView>> createProvider(
            @RequestBody AiConfigWriter.ProviderCommand command) {
        return guarded(() -> {
            AiProvider created = this.writer.createProvider(command);
            return toView(created, this.configService.modelsOf(created.getId()));
        });
    }

    @PutMapping("/providers/{id}")
    public ResponseEntity<ApiResponse<ProviderView>> updateProvider(@PathVariable Long id,
            @RequestBody AiConfigWriter.ProviderCommand command) {
        return guarded(() -> {
            AiProvider updated = this.writer.updateProvider(id, command);
            return toView(updated, this.configService.modelsOf(updated.getId()));
        });
    }

    /**
     * 删除 provider：默认软删（{@code enabled=false}）；{@code hard=true} 时只在无模型引用时真删。
     */
    @DeleteMapping("/providers/{id}")
    public ResponseEntity<ApiResponse<ProviderView>> deleteProvider(@PathVariable Long id,
            @RequestParam(name = "hard", defaultValue = "false") boolean hard) {
        return guarded(() -> {
            AiProvider deleted = this.writer.deleteProvider(id, hard);
            return toView(deleted, hard ? List.of() : this.configService.modelsOf(deleted.getId()));
        });
    }

    // ── 测试连接 / 拉取模型 ──────────────────────────────────────────────────

    /** 保存后验证一次：{@code GET {base}{modelsPath}}（超时 + 四档稳定 code）。 */
    @PostMapping("/providers/{id}/test")
    public ResponseEntity<ApiResponse<ProbeView>> testProvider(@PathVariable Long id) {
        return guarded(() -> {
            AiProvider provider = this.configService.provider(id)
                .orElseThrow(() -> new AiConfigException(AiConfigWriter.CODE_PROVIDER_NOT_FOUND,
                        "provider 不存在：" + id));
            AiConfigService.KeyResolution key = this.configService.resolveKey(provider);
            // M-1（复审）：key 声明了但本进程解不开（KEK 缺失/变更/密文损坏）⇒ 探活没有意义，
            // 直接把稳定码报出去。否则会拿 null key 去打上游，回来一个 AUTH_FAILED ——
            // 用户去改 key 也修不好（真正要修的是 KEK），排障方向被带偏。
            if (key.status() == AiConfigService.KeyStatus.UNREADABLE) {
                return new ProbeView(false, null, 0L, AiConfigService.CODE_KEY_UNREADABLE,
                        "库里的密钥无法解密：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置", 0);
            }
            // 种子行（source=ENV_SEED）是受信来源：私网闸对它例外（CI 的 127.0.0.1 桩）
            boolean trusted = AiProvider.SOURCE_ENV_SEED.equals(provider.getSource());
            AiConnectionProbe.ProbeResult result = this.probe.fetchModels(provider.getBaseUrl(),
                    provider.getModelsPath(), key.apiKey(), trusted);
            return new ProbeView(result.ok(), result.status(), result.latencyMs(), result.code(), result.message(),
                    result.modelCount());
        });
    }

    /** 拉取远端模型 id（不落库；Redis 缓存 10min）。{@code refresh=false} 时允许直接读缓存。 */
    @PostMapping("/providers/{id}/models/fetch")
    public ResponseEntity<ApiResponse<FetchModelsView>> fetchModels(@PathVariable Long id,
            @RequestParam(name = "refresh", defaultValue = "true") boolean refresh) {
        return guarded(() -> {
            AiModelCatalog.CatalogResult result = this.catalog.fetch(id, refresh);
            return new FetchModelsView(result.ok(), result.models(), result.cached(), result.code(),
                    result.message());
        });
    }

    // ── 模型 allowlist ──────────────────────────────────────────────────────

    @GetMapping("/providers/{id}/models")
    public ResponseEntity<ApiResponse<List<ModelView>>> models(@PathVariable Long id) {
        return guarded(() -> {
            requireProvider(id);
            return this.configService.modelsOf(id).stream().map(AiConfigController::toModelView).toList();
        });
    }

    @PostMapping("/providers/{id}/models")
    public ResponseEntity<ApiResponse<ModelView>> addModel(@PathVariable Long id,
            @RequestBody AiConfigWriter.ModelCommand command) {
        return guarded(() -> toModelView(this.writer.addModel(id, command)));
    }

    @PutMapping("/providers/{id}/models/{modelId}")
    public ResponseEntity<ApiResponse<ModelView>> updateModel(@PathVariable Long id,
            @PathVariable String modelId, @RequestBody AiConfigWriter.ModelCommand command) {
        return guarded(() -> toModelView(this.writer.updateModel(id, modelId, command)));
    }

    @DeleteMapping("/providers/{id}/models/{modelId}")
    public ResponseEntity<ApiResponse<ModelView>> deleteModel(@PathVariable Long id,
            @PathVariable String modelId) {
        return guarded(() -> toModelView(this.writer.deleteModel(id, modelId)));
    }

    // ── 全局默认参数 ────────────────────────────────────────────────────────

    @GetMapping("/settings/defaults")
    public ApiResponse<DefaultsView> defaults() {
        return ApiResponse.ok(toView(this.configService.defaults()));
    }

    @PutMapping("/settings/defaults")
    public ResponseEntity<ApiResponse<DefaultsView>> updateDefaults(
            @RequestBody AiConfigWriter.DefaultsCommand command) {
        return guarded(() -> toView(this.writer.updateDefaults(command)));
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private void requireProvider(Long id) {
        this.configService.provider(id)
            .orElseThrow(() -> new AiConfigException(AiConfigWriter.CODE_PROVIDER_NOT_FOUND,
                    "provider 不存在：" + id));
    }

    /** 把业务异常统一落成"信封 + 稳定 code + 对应 HTTP 状态"。 */
    private <T> ResponseEntity<ApiResponse<T>> guarded(java.util.function.Supplier<T> action) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(action.get()));
        }
        catch (AiConfigException ex) {
            return fail(ex.getStatus(), ex.getCode(), ex.getMessage());
        }
        catch (AiEndpointGuard.GuardException ex) {
            // 闸的异常若从写路径以外的口溢出（如直接校验 baseUrl），同样按 400 落
            return fail(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
        }
        catch (AiKeyCipher.KeyCipherException ex) {
            return fail(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> fail(HttpStatus status, String code, String message) {
        log.debug("配置写路径拒绝：{} {}", code, message);
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }

    private ProviderView toView(AiProvider provider, List<AiProviderModel> models) {
        return new ProviderView(provider.getId(), provider.getCode(), provider.getDisplayName(),
                provider.getProtocol(), provider.getBaseUrl(), provider.getCompletionsPath(),
                provider.getModelsPath(), provider.isKeyDeclared(), provider.getKeyLast4(), provider.getKeyRef(),
                // 运行时结论（OK/MISSING/UNREADABLE/DISABLED）：设置页要能直接看到"key 解不开"
                this.configService.keyStatus(provider), provider.getSource(), provider.isEnabled(),
                models.stream().map(AiConfigController::toModelView).toList());
    }

    private static ModelView toModelView(AiProviderModel model) {
        return new ModelView(model.getId(), model.getModelId(),
                model.getDisplayName() == null ? model.getModelId() : model.getDisplayName(),
                model.isSupportsToolCalling(), Boolean.TRUE.equals(model.getSupportsVision()),
                Boolean.TRUE.equals(model.getSupportsReasoning()), model.getMaxContext(), model.getMaxOutput(),
                model.isEnabled(), model.isDefault(), model.getSortOrder());
    }

    private static DefaultsView toView(AiConfigService.Defaults defaults) {
        return new DefaultsView(defaults.modelId(), defaults.temperature(), defaults.topP(), defaults.maxTokens(),
                defaults.allowUserParams(), defaults.version(), defaults.providerId());
    }
}
