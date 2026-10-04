package io.souqly.returns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReturnsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReturnsServiceApplication.class, args);
    }
}
