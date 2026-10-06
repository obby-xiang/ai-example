package com.example.configmgr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ConfigMgrApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigMgrApplication.class, args);
    }
}
