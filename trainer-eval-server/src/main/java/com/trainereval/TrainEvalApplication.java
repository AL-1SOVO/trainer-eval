package com.trainereval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TrainEvalApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrainEvalApplication.class, args);
    }
}
