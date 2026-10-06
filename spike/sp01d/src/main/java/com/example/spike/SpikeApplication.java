package com.example.spike;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpikeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpikeApplication.class, args);
    }
}
