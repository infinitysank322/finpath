package com.finpath.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Document metadata for {@code /v3/api-docs}: the bearer scheme and the money contract. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI finpathOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FinPath API").version("v1")
                        .description("All money amounts are JSON integers in paise (1 rupee = 100 paise). "
                                + "Never floats; INR formatting is done by the client. "
                                + "Errors are {\"status\":<http status>,\"error\":\"<fixed code>\"}. "
                                + "See docs/api-conventions.md."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
