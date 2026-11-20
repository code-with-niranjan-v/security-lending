package com.example.lendora_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(
  com.example.platform.RuntimeConfiguration.class
)
@SpringBootApplication
public class LendoraServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LendoraServiceApplication.class, args);
  }
}
