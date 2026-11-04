package com.example.configmgr.ai.config.db;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 全局配置键值仓储（主键 = 键名）。 */
public interface AiSettingRepository extends JpaRepository<AiSetting, String> {

    Optional<AiSetting> findBySettingKey(String settingKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select setting from AiSetting setting where setting.settingKey = :settingKey")
    Optional<AiSetting> findBySettingKeyForUpdate(@Param("settingKey") String settingKey);
}
