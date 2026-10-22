package com.finpath.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * In the {@code prod} profile, turns the single {@code NEON_DATABASE_URL} environment variable
 * into the Spring datasource and Flyway properties:
 *
 * <ul>
 *   <li>{@code spring.datasource.url/username/password}: pooled endpoint, used by HikariCP;
 *   <li>{@code spring.flyway.url/user/password}: direct endpoint, used by startup migrations.
 * </ul>
 *
 * The property source is added last, so anything set explicitly wins. If {@code
 * spring.datasource.url} is already set (for example via {@code SPRING_DATASOURCE_URL}) this class
 * does nothing at all, so the datasource and Flyway never end up pointing at different databases.
 *
 * <p>When the datasource auto-configuration is excluded (the test classpath does this) there is
 * nothing to configure and nothing to fail, so the class stays out of the way.
 *
 * <p>Failures never include the variable's value.
 */
public class NeonDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String ENV_VAR = "NEON_DATABASE_URL";
    static final String SOURCE_NAME = "neonDatasource";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.acceptsProfiles(Profiles.of("prod")) || datasourceExcluded(env)) {
            return;
        }
        if (env.containsProperty("spring.datasource.url")) {
            return;
        }
        String raw = env.getProperty(ENV_VAR);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "Profile 'prod' needs " + ENV_VAR + " (or SPRING_DATASOURCE_URL, _USERNAME, _PASSWORD) in the environment.");
        }
        NeonJdbcUrl neon = NeonJdbcUrl.parse(raw);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", neon.pooledJdbcUrl());
        props.put("spring.datasource.username", neon.username());
        props.put("spring.datasource.password", neon.password());
        props.put("spring.flyway.url", neon.directJdbcUrl());
        props.put("spring.flyway.user", neon.username());
        props.put("spring.flyway.password", neon.password());
        env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, props));
    }

    private static boolean datasourceExcluded(ConfigurableEnvironment env) {
        List<String> excluded = Binder.get(env)
                .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
                .orElse(List.of());
        return excluded.contains(DataSourceAutoConfiguration.class.getName());
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor, so profiles and application.yml are resolved.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
