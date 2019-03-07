package org.ihtsdo.rvf.jira;

import net.rcarz.jiraclient.BasicCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

@Configuration
@PropertySources({
		@PropertySource("classpath:jira-service.properties"),
		@PropertySource(value = "file:${rvfConfigLocation}/jira-service.properties", ignoreResourceNotFound = true)
}
)
public class JiraServiceConfig {

	@Value("${rvf.jira.basicAuth.username}")
	private String jiraUserName;

	@Value("${rvf.jira.basicAuth.password}")
	private String jiraPassword;

	@Value("${rvf.jira.endpoint}")
	private String jiraEndpoint;

	@Bean
	public JiraClientFactory jiraClientFactory() {
		return new JiraClientFactoryImpl(new BasicCredentials(jiraUserName, jiraPassword), jiraEndpoint);
	}

}
