package org.ihtsdo.rvf.rest.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class RootController {

	/**
	 * The console rather than Swagger.
	 *
	 * <p>Both are still served. Swagger is the reference for every parameter and
	 * stays at {@code /swagger-ui.html}, linked from the console. But the first
	 * thing a person wants from this server is to run a validation and read the
	 * findings, and a generated API listing is a poor place to start.
	 */
	@RequestMapping(path = "/", method = RequestMethod.GET)
	@Hidden
	public void getRoot(HttpServletResponse response) throws IOException {
		response.sendRedirect("ui/");
	}
}

