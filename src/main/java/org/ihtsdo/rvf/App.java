package org.ihtsdo.rvf;

import org.ihtsdo.rvf.core.messaging.ActiveMQConnectionFactoryPrefetchCustomizer;
import org.ihtsdo.rvf.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.annotation.EnableJms;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import static com.google.common.base.Predicates.not;
import static springfox.documentation.builders.PathSelectors.regex;

@EnableJms
@EnableSwagger2
public class App extends Config {
	private static Logger logger = LoggerFactory.getLogger(App.class);
	@Bean
	// The default queue prefetch size is 1,000. That prevents autoscaling rvf
	public ActiveMQConnectionFactoryPrefetchCustomizer queuePrefetchCustomizer(@Value("${spring.activemq.queuePrefetch:1}") int queuePrefetch) {
		return new ActiveMQConnectionFactoryPrefetchCustomizer(queuePrefetch);
	}

	// Swagger Config
	@Bean
	public Docket swagger() {
		return new Docket(DocumentationType.SWAGGER_2)
				.select()
				.apis(RequestHandlerSelectors.any())
				.paths(not(regex("/error")))
				.build();
	}
	
	public static void main(String[] args) throws Exception {
		SpringApplication.run(App.class, args);
	}
}