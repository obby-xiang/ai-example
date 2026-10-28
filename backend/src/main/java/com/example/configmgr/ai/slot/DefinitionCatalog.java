package com.example.configmgr.ai.slot;

import java.util.List;

/**
 * 配置定义目录（{@link TaskSlotResolver} 的唯一外部数据依赖）。
 *
 * <p>生产实现见 {@link DefinitionSlotCatalog}（{@code DefinitionService#findAll}）；
 * 用例用 lambda 给固定目录，不需要 Spring/JPA。
 */
public interface DefinitionCatalog {

	List<SlotDefinition> list();
}
