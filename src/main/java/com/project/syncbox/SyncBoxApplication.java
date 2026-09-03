package com.project.syncbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SyncBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SyncBoxApplication.class, args);
    }
}
