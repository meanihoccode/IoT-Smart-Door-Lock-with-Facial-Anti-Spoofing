package com.example.btl_iot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BtlIotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BtlIotApplication.class, args);
    }

}
