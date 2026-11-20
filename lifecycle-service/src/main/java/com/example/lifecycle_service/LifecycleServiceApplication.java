package com.example.lifecycle_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Import(
  com.example.platform.RuntimeConfiguration.class
)
@SpringBootApplication
@EnableScheduling
public class LifecycleServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LifecycleServiceApplication.class, args);
  }
}
