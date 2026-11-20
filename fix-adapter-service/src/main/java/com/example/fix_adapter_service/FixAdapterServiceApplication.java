package com.example.fix_adapter_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(
  com.example.platform.RuntimeConfiguration.class
)
@SpringBootApplication
public class FixAdapterServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(FixAdapterServiceApplication.class, args);
  }
}
