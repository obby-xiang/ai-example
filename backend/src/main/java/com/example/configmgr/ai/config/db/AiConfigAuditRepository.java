package com.example.configmgr.ai.config.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 配置变更审计仓储（{@code ai_config_audit}；设计稿 §3.1 的 P2 表）。
 *
 * <p>
 * 批① 只建表；批② 写路径每一次变更都落一行（key 面只写 {@code last4} + 动作，见
 * {@link AiConfigAudit#getDiffJson()}）。读面只有"最近 N 条"一种用法（设置页不展示审计，
 * 这里给运维/测试一个可查的口；看板类查询后置）。
 */
public interface AiConfigAuditRepository extends JpaRepository<AiConfigAudit, Long> {

    List<AiConfigAudit> findAllByOrderByIdDesc();

    List<AiConfigAudit> findByTargetTypeOrderByIdDesc(String targetType);

}
