package com.example.configmgr.ai.config.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** provider 主档仓储（范式照 {@code AiFeedbackRepository}：JpaRepository + 方法名派生查询）。 */
public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    Optional<AiProvider> findByCode(String code);

    /** 设置页/快照的全量口径：稳定排序（sort_order 升序，同序按 id）。 */
    List<AiProvider> findAllByOrderBySortOrderAscIdAsc();

    /** 可用性判据的输入：只看启用的 provider。 */
    List<AiProvider> findByEnabledTrueOrderBySortOrderAscIdAsc();

    long countByCode(String code);
}
