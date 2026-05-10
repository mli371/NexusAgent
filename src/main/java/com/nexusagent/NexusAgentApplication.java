package com.nexusagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NexusAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusAgentApplication.class, args);
    }
}
