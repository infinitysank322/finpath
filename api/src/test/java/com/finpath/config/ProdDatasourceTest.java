package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the prod datasource binds from environment variables WITHOUT relying on the test
 * classpath's config/application.yml, which excludes DataSource/Flyway auto-configuration for every
 * Spring context test (so ProfilesTest cannot see this). Here the real main application.yml prod
 * document and the real post-processor run against a mock environment. No database is started.
 */
class ProdDatasourceTest {

    private static final String SEP = ":" + "//";
    private static final String USER = "fake_user";
    private static final String PASS = "fake-pass-" + "1";
    private static final String NEON_LIKE = "postgresql" + SEP + USER + ":" + PASS
            + "@ep-fake-123-pooler.region.db.invalid/fakedb?sslmode=require&channel_binding=require";

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        // Mirror Spring's order: application.yml documents first, system environment after.
        List<PropertySource<?>> docs = ProdProfileYaml.prodDocuments();
        docs.forEach(env.getPropertySources()::addLast);
        return env;
    }

    private static void run(MockEnvironment env) {
        new NeonDatasourceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    }

    @Test
    void postProcessorIsRegisteredForSpringBoot() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertThat(in).isNotNull();
            factories.load(in);
        }
        assertThat(factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor"))
                .contains(NeonDatasourceEnvironmentPostProcessor.class.getName());
    }

    @Test
    void prodDatasourceAndFlywayBindFromTheEnvironmentVariable() {
        MockEnvironment env = prodEnv();
        env.setProperty("NEON_DATABASE_URL", NEON_LIKE);
        run(env);

        String url = env.getProperty("spring.datasource.url");
        assertThat(url).contains("ep-fake-123-pooler.region.db.invalid:5432/fakedb")
                .contains("sslmode=require")
                .contains("channelBinding=require")
                .doesNotContain(USER).doesNotContain(PASS).doesNotContain("@");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo(USER);
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo(PASS);

        String flywayUrl = env.getProperty("spring.flyway.url");
        assertThat(flywayUrl).contains("ep-fake-123.region.db.invalid:5432/fakedb")
                .doesNotContain("-pooler")
                .doesNotContain(USER).doesNotContain(PASS);
        assertThat(env.getProperty("spring.flyway.user")).isEqualTo(USER);
        assertThat(env.getProperty("spring.flyway.password")).isEqualTo(PASS);
    }

    @Test
    void prodHikariSettingsAreScaleToZeroSafe() {
        MockEnvironment env = prodEnv();
        env.setProperty("NEON_DATABASE_URL", NEON_LIKE);
        run(env);

        HikariConfig hikari = ProdHikari.bind(env);
        assertThat(hikari.getConnectionTimeout()).isGreaterThanOrEqualTo(10_000);
        assertThat(hikari.getInitializationFailTimeout()).isGreaterThanOrEqualTo(hikari.getConnectionTimeout());
        assertThat(hikari.getValidationTimeout()).isBetween(1_000L, 10_000L);
        assertThat(hikari.getConnectionTestQuery()).isEqualTo("SELECT 1");
        assertThat(hikari.getMaximumPoolSize()).isBetween(1, 10);
        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getMaxLifetime()).isLessThan(300_000L);
        assertThat(hikari.getKeepaliveTime()).isZero();
    }

    @Test
    void explicitSpringDatasourceOverridesLeaveEverythingUntouched() {
        MockEnvironment env = prodEnv();
        env.setProperty("NEON_DATABASE_URL", NEON_LIKE);
        env.setProperty("spring.datasource.url", "jdbc:stub:override");
        run(env);
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:stub:override");
        assertThat(env.getProperty("spring.datasource.username")).isNull();
        assertThat(env.getProperty("spring.flyway.url")).isNull();
    }

    @Test
    void missingUrlInProdFailsFastWithoutEchoingValues() {
        MockEnvironment env = prodEnv();
        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEON_DATABASE_URL");
    }

    @Test
    void badUrlInProdFailsFastWithoutEchoingValues() {
        MockEnvironment env = prodEnv();
        env.setProperty("NEON_DATABASE_URL", "postgresql" + SEP + USER + ":" + PASS + "@" + " bad host/fakedb");
        assertThatThrownBy(() -> run(env))
                .isInstanceOf(NeonJdbcUrl.InvalidNeonUrlException.class)
                .hasMessageNotContainingAny(USER, PASS, "bad host");
    }

    @Test
    void otherProfilesAreNotTouched() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("NEON_DATABASE_URL", NEON_LIKE);
        run(env);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void excludedDatasourceAutoConfigurationSkipsEvenInProd() {
        MockEnvironment env = prodEnv();
        env.setProperty("spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
        run(env);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    /** Binds the prod hikari block exactly as Spring Boot binds it onto HikariDataSource. */
    static final class ProdHikari {
        static HikariConfig bind(MockEnvironment env) {
            return Binder.get(env)
                    .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class))
                    .get();
        }
    }
}
