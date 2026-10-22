package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.config.NeonJdbcUrl.InvalidNeonUrlException;
import org.junit.jupiter.api.Test;

/** All hosts and credentials are fake and the scheme is assembled at runtime. */
class NeonJdbcUrlTest {

    private static final String SEP = ":" + "//";
    private static final String JDBC = "jdbc:" + "postgresql" + SEP;
    private static final String USER = "fake_user";
    private static final String PASS = "fake-pass-" + "1";
    private static final String POOLED_HOST = "ep-fake-123-pooler.region.db.invalid";
    private static final String DIRECT_HOST = "ep-fake-123.region.db.invalid";

    private static String url(String scheme, String userInfo, String hostPort, String pathAndQuery) {
        return scheme + SEP + userInfo + "@" + hostPort + pathAndQuery;
    }

    private static String neonLike(String query) {
        return url("postgresql", USER + ":" + PASS, POOLED_HOST, "/fakedb" + query);
    }

    @Test
    void splitsCredentialsOutOfTheJdbcUrls() {
        NeonJdbcUrl n = NeonJdbcUrl.parse(neonLike("?sslmode=require"));
        assertThat(n.username()).isEqualTo(USER);
        assertThat(n.password()).isEqualTo(PASS);
        assertThat(n.pooledJdbcUrl()).doesNotContain(USER).doesNotContain(PASS).doesNotContain("@");
        assertThat(n.directJdbcUrl()).doesNotContain(USER).doesNotContain(PASS).doesNotContain("@");
    }

    @Test
    void pooledKeepsPoolerHostAndDirectDropsIt() {
        NeonJdbcUrl n = NeonJdbcUrl.parse(neonLike("?sslmode=require"));
        assertThat(n.pooledJdbcUrl()).startsWith(JDBC + POOLED_HOST + ":5432/fakedb?");
        assertThat(n.directJdbcUrl()).startsWith(JDBC + DIRECT_HOST + ":5432/fakedb?");
    }

    @Test
    void hostWithoutPoolerSuffixIsItsOwnDirectEndpoint() {
        NeonJdbcUrl n = NeonJdbcUrl.parse(url("postgres", USER + ":" + PASS, DIRECT_HOST + ":6543", "/fakedb"));
        assertThat(n.pooledJdbcUrl()).startsWith(JDBC + DIRECT_HOST + ":6543/fakedb");
        assertThat(n.directJdbcUrl()).isEqualTo(n.pooledJdbcUrl());
    }

    @Test
    void onlyTheFirstLabelLosesItsPoolerSuffix() {
        assertThat(NeonJdbcUrl.directHost("ep-a-pooler.eu.db.invalid")).isEqualTo("ep-a.eu.db.invalid");
        assertThat(NeonJdbcUrl.directHost("ep-a.pooler-x.db.invalid")).isEqualTo("ep-a.pooler-x.db.invalid");
        assertThat(NeonJdbcUrl.directHost("-pooler.db.invalid")).isEqualTo("-pooler.db.invalid");
        assertThat(NeonJdbcUrl.directHost("localhost")).isEqualTo("localhost");
    }

    @Test
    void percentEncodedCredentialsAreDecodedAndPlusStaysPlus() {
        NeonJdbcUrl n = NeonJdbcUrl.parse(url("postgres", "u%40x:p%2Fa%3Ab+c%25", POOLED_HOST, "/fakedb"));
        assertThat(n.username()).isEqualTo("u@x");
        assertThat(n.password()).isEqualTo("p/a:b+c%");
    }

    @Test
    void sslmodeDefaultsToRequireAndIsKept() {
        assertThat(NeonJdbcUrl.parse(neonLike("")).pooledJdbcUrl()).contains("sslmode=require");
        assertThat(NeonJdbcUrl.parse(neonLike("?sslmode=verify-full")).pooledJdbcUrl()).contains("sslmode=verify-full");
    }

    /**
     * pgJDBC 42.7.x (Boot 3.5.16 manages 42.7.11) defines PGProperty.CHANNEL_BINDING under the
     * camelCase name "channelBinding"; libpq's "channel_binding" is not a name it knows. The
     * converter therefore renames and validates it, and the driver check below pins that fact.
     */
    @Test
    void channelBindingIsRenamedToThePgJdbcSpelling() {
        String jdbc = NeonJdbcUrl.parse(neonLike("?sslmode=require&channel_binding=require")).pooledJdbcUrl();
        assertThat(jdbc).contains("channelBinding=require").doesNotContain("channel_binding");
    }

    @Test
    void driverStillKnowsTheRenamedProperty() {
        assertThat(org.postgresql.PGProperty.forName("channelBinding")).isEqualTo(org.postgresql.PGProperty.CHANNEL_BINDING);
        assertThat(org.postgresql.PGProperty.forName("channel_binding")).isNull();
    }

    @Test
    void unknownParametersAreDroppedAndConnectTimeoutIsLifted() {
        String jdbc = NeonJdbcUrl.parse(neonLike("?options=x&application_name=y&sslmode=require")).pooledJdbcUrl();
        assertThat(jdbc).doesNotContain("options").doesNotContain("application_name");
        assertThat(jdbc).endsWith("?sslmode=require&connectTimeout=30");
    }

    @Test
    void invalidValuesAreRejectedWithoutEchoingThem() {
        assertThatThrownBy(() -> NeonJdbcUrl.parse(neonLike("?sslmode=bogus-value-xyz")))
                .isInstanceOf(InvalidNeonUrlException.class)
                .hasMessageNotContainingAny("bogus-value-xyz", USER, PASS, POOLED_HOST);
        assertThatThrownBy(() -> NeonJdbcUrl.parse(neonLike("?channel_binding=bogus-value-xyz")))
                .isInstanceOf(InvalidNeonUrlException.class)
                .hasMessageNotContainingAny("bogus-value-xyz", USER, PASS, POOLED_HOST);
    }

    @Test
    void malformedInputFailsWithoutEchoingOrChaining() {
        String bad = neonLike("") + " with space";
        assertThatThrownBy(() -> NeonJdbcUrl.parse(bad))
                .isInstanceOf(InvalidNeonUrlException.class)
                .hasMessageNotContainingAny(USER, PASS, POOLED_HOST)
                .hasNoCause();
        assertThatThrownBy(() -> NeonJdbcUrl.parse(url("postgres", "u:p%zz", POOLED_HOST, "/fakedb")))
                .isInstanceOf(InvalidNeonUrlException.class)
                .hasMessageNotContainingAny(POOLED_HOST);
    }

    @Test
    void structuralProblemsAreRejected() {
        assertThatThrownBy(() -> NeonJdbcUrl.parse(null)).isInstanceOf(InvalidNeonUrlException.class);
        assertThatThrownBy(() -> NeonJdbcUrl.parse("  ")).isInstanceOf(InvalidNeonUrlException.class);
        assertThatThrownBy(() -> NeonJdbcUrl.parse(url("mysql", USER + ":" + PASS, POOLED_HOST, "/fakedb")))
                .isInstanceOf(InvalidNeonUrlException.class).hasMessageContaining("scheme");
        assertThatThrownBy(() -> NeonJdbcUrl.parse("postgres" + SEP + POOLED_HOST + "/fakedb"))
                .isInstanceOf(InvalidNeonUrlException.class).hasMessageContaining("user");
        assertThatThrownBy(() -> NeonJdbcUrl.parse(url("postgres", USER, POOLED_HOST, "/fakedb")))
                .isInstanceOf(InvalidNeonUrlException.class);
        assertThatThrownBy(() -> NeonJdbcUrl.parse(url("postgres", USER + ":" + PASS, POOLED_HOST, "")))
                .isInstanceOf(InvalidNeonUrlException.class).hasMessageContaining("database");
        assertThatThrownBy(() -> NeonJdbcUrl.parse(url("postgres", USER + ":" + PASS, POOLED_HOST, "/")))
                .isInstanceOf(InvalidNeonUrlException.class).hasMessageContaining("database");
    }

    @Test
    void toStringNeverLeaksValues() {
        NeonJdbcUrl n = NeonJdbcUrl.parse(neonLike("?sslmode=require"));
        assertThat(n.toString()).doesNotContain(USER).doesNotContain(PASS).doesNotContain(POOLED_HOST);
    }
}
