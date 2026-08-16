package com.watcherservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WatcherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WatcherServiceApplication.class, args);
    }

}
