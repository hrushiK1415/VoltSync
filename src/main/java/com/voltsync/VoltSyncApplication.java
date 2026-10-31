package com.voltsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class VoltSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoltSyncApplication.class, args);
    }
}
