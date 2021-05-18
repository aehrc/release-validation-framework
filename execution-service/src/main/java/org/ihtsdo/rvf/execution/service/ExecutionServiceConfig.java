package org.ihtsdo.rvf.execution.service;

import org.ihtsdo.otf.jms.MessagingHelper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

@Configuration
@PropertySources({
	@PropertySource(value = "classpath:execution-service-defaults.properties"),
	@PropertySource(value = "file:${rvfConfigLocation}/execution-service.properties", ignoreResourceNotFound=true)})
public class ExecutionServiceConfig {

	@Bean
	public MessagingHelper messagingHelper() {
		return new MessagingHelper();
	}
}
