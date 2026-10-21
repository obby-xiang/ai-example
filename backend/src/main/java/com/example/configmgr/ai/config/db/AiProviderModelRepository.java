package com.example.configmgr.ai.config.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 模型 allowlist 仓储（唯一键 {@code (provider_id, model_id)}）。 */
public interface AiProviderModelRepository extends JpaRepository<AiProviderModel, Long> {

    List<AiProviderModel> findByProviderIdOrderBySortOrderAscIdAsc(Long providerId);

    List<AiProviderModel> findByProviderIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long providerId);

    /** 快照一次性取全量，避免 N+1（读路径每请求只读内存，不逐 provider 查表）。 */
    List<AiProviderModel> findByProviderIdInOrderBySortOrderAscIdAsc(Collection<Long> providerIds);

    Optional<AiProviderModel> findByProviderIdAndModelId(Long providerId, String modelId);

    List<AiProviderModel> findByProviderId(Long providerId);
}
