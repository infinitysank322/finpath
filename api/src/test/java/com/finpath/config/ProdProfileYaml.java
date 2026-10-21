package com.finpath.config;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the main application.yml directly (not through the test classpath's config/application.yml,
 * which switches the datasource off) and returns the documents that apply to the prod profile.
 */
final class ProdProfileYaml {

    private ProdProfileYaml() {
    }

    static List<PropertySource<?>> prodDocuments() {
        try {
            List<PropertySource<?>> all = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            return all.stream()
                    .filter(ProdProfileYaml::appliesToProd)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read application.yml", e);
        }
    }

    private static boolean appliesToProd(PropertySource<?> doc) {
        Object profile = doc.getProperty("spring.config.activate.on-profile");
        return "prod".equals(profile);
    }
}
