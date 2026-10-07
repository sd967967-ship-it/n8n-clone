package com.automationstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AutomationStudioApplication {
  public static void main(String[] args) {
    SpringApplication.run(AutomationStudioApplication.class, args);
  }
}
