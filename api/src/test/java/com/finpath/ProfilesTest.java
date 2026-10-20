package com.finpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

    private static void assertEnvNotExposed(TestRestTemplate rest) {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    class Local {
        @Autowired
        private TestRestTemplate rest;

        @Test
        void healthIsExactlyStatusUp() throws Exception {
            assertJsonEquals(rest, "/actuator/health", "{\"status\":\"UP\"}");
        }

        @Test
        void envEndpointIsNotExposed() {
            assertEnvNotExposed(rest);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("ci")
    class Ci {
        @Autowired
        private TestRestTemplate rest;

        @Test
        void healthIsExactlyStatusUp() throws Exception {
            assertJsonEquals(rest, "/actuator/health", "{\"status\":\"UP\"}");
        }

        @Test
        void envEndpointIsNotExposed() {
            assertEnvNotExposed(rest);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("prod")
    class Prod {
        @Autowired
        private TestRestTemplate rest;

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
            assertEnvNotExposed(rest);
        }
    }
}
