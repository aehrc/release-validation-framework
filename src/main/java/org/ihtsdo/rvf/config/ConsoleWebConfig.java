package org.ihtsdo.rvf.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes {@code /ui} and {@code /ui/} serve the console.
 *
 * <p>Spring Boot serves {@code classpath:/static/ui/index.html} at
 * {@code /ui/index.html}, and its welcome-page mapping only covers the context
 * root - so {@code /ui/} answered 404 while {@code /ui/app.js} beside it
 * answered 200. Verified against the deployed service before writing this.
 *
 * <p>The two paths are handled differently on purpose.
 *
 * <p>{@code /ui/} is <em>forwarded</em>, which keeps the browser's address at
 * {@code /ui/}. That matters because the page addresses its assets relatively
 * ({@code app.js}) and the API as {@code ..}, both of which resolve correctly
 * from a directory path.
 *
 * <p>{@code /ui} is <em>redirected</em> to {@code /ui/} rather than forwarded.
 * A forward would leave the address without the trailing slash, where the
 * browser treats the last segment as a file: {@code app.js} would resolve to
 * {@code /app.js} and the page would load with no styling and no behaviour.
 */
@Configuration
public class ConsoleWebConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController("/ui", "/ui/");
		registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
	}
}
