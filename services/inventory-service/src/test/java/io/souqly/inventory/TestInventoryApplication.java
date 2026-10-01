package io.souqly.inventory;

import org.springframework.boot.SpringApplication;

/** Runs the service locally against throwaway containers: {@code ./mvnw spring-boot:test-run}. */
public class TestInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.from(InventoryApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
