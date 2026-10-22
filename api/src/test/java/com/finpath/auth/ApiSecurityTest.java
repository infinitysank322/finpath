package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** T29 acceptance: bearer-JWT protection of the API, error shape, and principal resolution. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Import(ApiSecurityTest.Config.class)
class ApiSecurityTest {

    private static final String PROTECTED = "/test-protected/me";

    @RestController
    static class WhoAmI {
        @GetMapping(PROTECTED)
        Map<String, String> me(@CurrentUserId UUID userId) {
            return Map.of("userId", userId.toString(),
                    "seam", CurrentUser.id().map(UUID::toString).orElse("none"));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        WhoAmI whoAmI() {
            return new WhoAmI();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtIssuer issuer;

    @Autowired
    private JwtEncoder encoder;

    private ResultActions call(String bearer) throws Exception {
        return mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + bearer));
    }

    private static void assertJson401(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.oneOf(
                        "Bearer", "Bearer error=\"invalid_token\"")))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void noBearerTokenIs401WithJsonBody() throws Exception {
        ResultActions result = mvc.perform(get(PROTECTED));
        assertJson401(result);
        result.andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void invalidTokenChallengeCarriesNoDecoderText() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String expired = encoder.encode(params(claims(UUID.randomUUID())
                .issuedAt(past).expiresAt(past.plus(15, ChronoUnit.MINUTES)).build())).getTokenValue();
        call(expired).andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        call("not.a.jwt").andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutExpIs401() throws Exception {
        JwtClaimsSet noExp = JwtClaimsSet.builder().issuer(JwtIssuer.ISSUER).subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).build();
        assertThat(noExp.getExpiresAt()).isNull();
        assertJson401(call(encoder.encode(params(noExp)).getTokenValue()));
    }

    @Test
    void anyUnknownPathAlsoNeedsAToken() throws Exception {
        assertJson401(mvc.perform(get("/anything-else")));
    }

    @Test
    void validTokenPassesAndThePrincipalResolvesToTheUserId() throws Exception {
        UUID user = UUID.randomUUID();
        call(issuer.issue(user).value())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andExpect(jsonPath("$.seam").value(user.toString()));
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        byte[] otherKey = new byte[48];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        String forged = forger.encode(params(claims(UUID.randomUUID()).build())).getTokenValue();
        assertJson401(call(forged).andExpect(jsonPath("$.error").value("invalid_token")));
    }

    @Test
    void tamperedPayloadWithOriginalSignatureIs401() throws Exception {
        String[] parts = issuer.issue(UUID.randomUUID()).value().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String otherSub = payload.replaceAll("\"sub\":\"[^\"]+\"", "\"sub\":\"" + UUID.randomUUID() + "\"");
        assertThat(otherSub).isNotEqualTo(payload);
        String tampered = parts[0] + "." + b64(otherSub) + "." + parts[2];
        assertJson401(call(tampered));
    }

    @Test
    void flippedSignatureCharacterIs401() throws Exception {
        String token = issuer.issue(UUID.randomUUID()).value();
        int i = token.lastIndexOf('.') + 5;
        char flipped = token.charAt(i) == 'A' ? 'B' : 'A';
        assertJson401(call(token.substring(0, i) + flipped + token.substring(i + 1)));
    }

    @Test
    void unsignedAlgNoneTokenIs401() throws Exception {
        String unsigned = b64("{\"alg\":\"none\"}") + "." + b64("{\"iss\":\"finpath\",\"sub\":\""
                + UUID.randomUUID() + "\",\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond() + "}") + ".";
        assertJson401(call(unsigned));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String expired = encoder.encode(params(claims(UUID.randomUUID())
                .issuedAt(past).expiresAt(past.plus(15, ChronoUnit.MINUTES)).build())).getTokenValue();
        assertJson401(call(expired));
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        String token = encoder.encode(params(claims(UUID.randomUUID()).issuer("someone-else").build()))
                .getTokenValue();
        assertJson401(call(token));
    }

    @Test
    void subjectThatIsNotAUserIdIs401() throws Exception {
        JwtClaimsSet.Builder claims = claims(UUID.randomUUID()).subject("admin");
        assertJson401(call(encoder.encode(params(claims.build())).getTokenValue()));
    }

    @Test
    void healthStaysPublicAndSessionless() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void authenticatedApiCallCreatesNoSession() throws Exception {
        call(issuer.issue(UUID.randomUUID()).value())
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    private static JwtClaimsSet.Builder claims(UUID sub) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer(JwtIssuer.ISSUER).subject(sub.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600));
    }

    private static JwtEncoderParameters params(JwtClaimsSet claims) {
        return JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
