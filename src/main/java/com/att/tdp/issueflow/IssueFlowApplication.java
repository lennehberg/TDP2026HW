package com.att.tdp.issueflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan picks up @ConfigurationProperties-annotated
// records (e.g. JwtProperties) anywhere under this package without each
// config class needing its own @EnableConfigurationProperties.
@SpringBootApplication
@ConfigurationPropertiesScan
public class IssueFlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(IssueFlowApplication.class, args);
	}

}
