package com.finpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.auth.JwtIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

class ProfilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void assertJsonEquals(TestRestTemplate rest, String path, String expected) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(response.getBody())).isEqualTo(JSON.readTree(expected));
    }

    /**
     * T29: security now sits in front of actuator, so an anonymous caller gets 401. The real check
     * is that even an authenticated caller gets 404, i.e. the endpoint is not exposed at all.
     */
    private static void assertEnvNotExposed(TestRestTemplate rest, JwtIssuer issuer) {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(issuer.issue(UUID.randomUUID()).value());
        assertThat(rest.exchange("/actuator/env", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    class Local {
        @Autowired
        private TestRestTemplate rest;

        @Autowired
        private JwtIssuer issuer;

        @Test
        void healthIsExactlyStatusUp() throws Exception {
            assertJsonEquals(rest, "/actuator/health", "{\"status\":\"UP\"}");
        }

        @Test
        void envEndpointIsNotExposed() {
            assertEnvNotExposed(rest, issuer);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("ci")
    class Ci {
        @Autowired
        private TestRestTemplate rest;

        @Autowired
        private JwtIssuer issuer;

        @Test
        void healthIsExactlyStatusUp() throws Exception {
            assertJsonEquals(rest, "/actuator/health", "{\"status\":\"UP\"}");
        }

        @Test
        void envEndpointIsNotExposed() {
            assertEnvNotExposed(rest, issuer);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("prod")
    class Prod {
        @Autowired
        private TestRestTemplate rest;

        @Autowired
        private JwtIssuer issuer;

        // Probes are on in prod, which adds the group names; pin that shape so no details or components creep in.
        @Test
        void healthIsStatusUpWithProbeGroupsOnly() throws Exception {
            assertJsonEquals(rest, "/actuator/health",
                    "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}");
        }

        @Test
        void probesAreUp() throws Exception {
            assertJsonEquals(rest, "/actuator/health/liveness", "{\"status\":\"UP\"}");
            assertJsonEquals(rest, "/actuator/health/readiness", "{\"status\":\"UP\"}");
        }

        @Test
        void envEndpointIsNotExposed() {
            assertEnvNotExposed(rest, issuer);
        }
    }
}
