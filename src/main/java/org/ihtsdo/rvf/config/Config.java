package org.ihtsdo.rvf.config;

import org.ihtsdo.otf.jms.MessagingHelper;
import org.ihtsdo.rvf.core.service.config.DatabaseServiceConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@SpringBootApplication
@Configuration
@EnableConfigurationProperties
public class Config extends DatabaseServiceConfig {
	
	@Configuration
	@EnableWebSecurity
	@Order(1)
	public static class ApiWebSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {
		protected void configure(HttpSecurity http) throws Exception {
			http.authorizeRequests()
				.antMatchers("/swagger-ui.html",
							"/swagger-resources/**",
							"/v2/api-docs",
							"/v2/**",
							"/webjars/springfox-swagger-ui/**")
				.permitAll();
			http.csrf().disable();
		}
	}

	@Bean
	public MessagingHelper messagingHelper() {
		return new MessagingHelper();
	}
}
