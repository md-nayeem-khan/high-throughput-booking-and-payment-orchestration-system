package com.booking.orchestration.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Orchestration Service Application Coordinates saga workflows with compensation logic */
@SpringBootApplication
public class OrchestrationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrchestrationServiceApplication.class, args);
  }
}
