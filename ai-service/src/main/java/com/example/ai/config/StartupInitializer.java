package com.example.ai.config;

import com.example.ai.service.ConfigDefinitionService;
import com.example.ai.service.DataSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupInitializer implements CommandLineRunner {

    private final ConfigDefinitionService configDefinitionService;
    private final DataSeedService dataSeedService;

    @Override
    public void run(String... args) {
        configDefinitionService.initSeedDataIfEmpty(dataSeedService);
        log.info("===== AI Service 启动完成 =====");
    }
}
