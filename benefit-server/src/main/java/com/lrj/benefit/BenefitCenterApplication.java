package com.lrj.benefit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BenefitCenterApplication {
    public static void main(String[] args) {
        SpringApplication.run(BenefitCenterApplication.class, args);
    }
}
