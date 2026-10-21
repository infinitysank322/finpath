package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Pins the prod forwarded-header policy: native (Tomcat RemoteIpValve), default
 * strategy never "framework", and X-Forwarded-* honoured only from a trusted proxy peer.
 */
class ForwardedHeadersTest {

    @Test
    void prodYamlUsesNativeStrategyNotFramework() {
        String value = String.valueOf(ProdProfileYaml.prodDocuments().stream()
                .map(d -> d.getProperty("server.forward-headers-strategy"))
                .filter(v -> v != null)
                .findFirst().orElseThrow());
        assertThat(value).isEqualTo("${SERVER_FORWARD_HEADERS_STRATEGY:native}");
    }

    @RestController
    static class SchemeEcho {
        @GetMapping("/test-scheme")
        String scheme(HttpServletRequest request) {
            return request.getScheme();
        }
    }

    @TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        SchemeEcho schemeEcho() {
            return new SchemeEcho();
        }
    }

    private static String get(TestRestTemplate rest) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-Proto", "https");
        headers.add("X-Forwarded-For", "203.0.113.9");
        return rest.exchange("/test-scheme", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = "server.forward-headers-strategy=native")
    @ActiveProfiles("ci")
    @org.springframework.context.annotation.Import(Config.class)
    class TrustedPeer {
        @Autowired
        private TestRestTemplate rest;

        @Test
        void loopbackProxyHeadersAreHonoured() {
            assertThat(get(rest)).isEqualTo("https");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"server.forward-headers-strategy=native",
                    "server.tomcat.remoteip.internal-proxies=192[.]0[.]2[.]1"})
    @ActiveProfiles("ci")
    @org.springframework.context.annotation.Import(Config.class)
    class UntrustedPeer {
        @Autowired
        private TestRestTemplate rest;

        @Test
        void spoofedHeadersFromUntrustedPeerAreIgnored() {
            assertThat(get(rest)).isEqualTo("http");
        }
    }
}
