package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;

/** T32: the OpenAPI document is opt-in in prod (a smaller public surface) via OPENAPI_ENABLED. */
class ProdOpenApiYamlTest {

    @Test
    void prodDisablesTheApiDocsUnlessTheEnvironmentOptsIn() {
        Object value = ProdProfileYaml.prodDocuments().stream()
                .map((PropertySource<?> d) -> d.getProperty("springdoc.api-docs.enabled"))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        assertThat(value).isEqualTo("${OPENAPI_ENABLED:false}");
    }
}
