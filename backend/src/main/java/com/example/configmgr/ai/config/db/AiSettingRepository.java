package com.example.configmgr.ai.config.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 全局配置键值仓储（主键 = 键名）。 */
public interface AiSettingRepository extends JpaRepository<AiSetting, String> {

    Optional<AiSetting> findBySettingKey(String settingKey);
}
