package com.example.lender_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(
  com.example.platform.RuntimeConfiguration.class
)
@SpringBootApplication
public class LenderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LenderServiceApplication.class, args);
  }
}
