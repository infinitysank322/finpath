package com.finpath.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two filter chains.
 * <ol>
 *   <li>Google sign-in ({@code /oauth2/authorization/**}, {@code /login/oauth2/code/**}): Spring's
 *       oauth2Login as a confidential client. The only place a session exists, and only for the
 *       few seconds between the redirect to Google and the callback.</li>
 *   <li>Everything else: a stateless bearer-JWT resource server. Health, the OpenAPI document and the token
 *       endpoints are public; every other path needs {@code Authorization: Bearer <jwt>}. No cookies are
 *       read, so CSRF protection is off for this chain only. CORS (T33) applies to this chain only:
 *       exact origins from FINPATH_CORS_ALLOWED_ORIGINS, no credentials. The sign-in chain is
 *       top-level browser navigation and gets no CORS handling.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class})
public class SecurityConfig {

    static final String GOOGLE = "google";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    @Order(1)
    SecurityFilterChain googleLoginChain(HttpSecurity http, ClientRegistrationRepository registrations,
            ReturnUrlAllowList allowList, GoogleLoginHandlers handlers) throws Exception {
        requireGoogleClient(registrations);
        http.securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new ReturnUrlGuardFilter(allowList), OAuth2AuthorizationRequestRedirectFilter.class)
                .oauth2Login(o -> o
                        // Resolver failures (bad parameters, unknown registration id) are 400/404 JSON, never 500.
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                            @Override
                            public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(
                                        ReturnUrlAuthorizationRequestResolver.failureHandler());
                                return filter;
                            }
                        })
                        .authorizationEndpoint(e -> e
                                .authorizationRequestResolver(
                                        new ReturnUrlAuthorizationRequestResolver(registrations, allowList))
                                .authorizationRequestRepository(new ReturnUrlExposingAuthorizationRequestRepository()))
                        .authorizedClientRepository(new NoStoreAuthorizedClientRepository())
                        .successHandler(handlers)
                        .failureHandler(handlers));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, CorsProperties cors) throws Exception {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint();
        // CorsFilter runs before authentication: a preflight from an allowed origin is answered 200
        // without a token, a disallowed origin gets 403 with no ACAO, and a 401 still carries ACAO
        // so the web can read it and refresh.
        http.cors(c -> c.configurationSource(corsSource(cors)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/token", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/error").permitAll()
                        // T32: the OpenAPI document describes routes and schemas only, never data.
                        // It is off in prod unless OPENAPI_ENABLED=true (404 then); Swagger UI is not shipped.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(JsonAuthenticationEntryPoint.accessDeniedHandler()))
                .oauth2ResourceServer(r -> r
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(JsonAuthenticationEntryPoint.accessDeniedHandler())
                        .jwt(jwt -> { }));
        return http.build();
    }

    /** Not a bean on purpose, so nothing else (the sign-in chain, Spring MVC) picks it up. */
    static CorsConfigurationSource corsSource(CorsProperties cors) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins());   // exact match; empty = no cross-origin caller
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(false);                 // bearer tokens only, never cookies
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties, DeletedUsers deletedUsers) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // JwtTimestampValidator accepts a token with no exp at all, so require it explicitly.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(JwtIssuer.ISSUER),
                subjectIsUserId(),
                subjectNotDeleted(deletedUsers)));
        return decoder;
    }

    private static SecretKey signingKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** sub must be a users.id, so {@link CurrentUser} can never see anything else. */
    private static OAuth2TokenValidator<Jwt> subjectIsUserId() {
        OAuth2Error invalid = new OAuth2Error("invalid_token", "sub is not a user id", null);
        return jwt -> {
            try {
                UUID.fromString(String.valueOf(jwt.getSubject()));
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException e) {
                return OAuth2TokenValidatorResult.failure(invalid);
            }
        };
    }

    /** A token for a deleted account (DELETE /me) is refused until it would have expired anyway. */
    private static OAuth2TokenValidator<Jwt> subjectNotDeleted(DeletedUsers deletedUsers) {
        OAuth2Error deleted = new OAuth2Error("invalid_token", "account deleted", null);
        return jwt -> {
            try {
                return deletedUsers.isDeleted(UUID.fromString(String.valueOf(jwt.getSubject())))
                        ? OAuth2TokenValidatorResult.failure(deleted)
                        : OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException e) {
                return OAuth2TokenValidatorResult.success();   // subjectIsUserId reports a malformed sub
            }
        };
    }

    /** Fail fast: Spring Boot only checks the client id, not that a secret is present. */
    private static void requireGoogleClient(ClientRegistrationRepository registrations) {
        ClientRegistration google = registrations.findByRegistrationId(GOOGLE);
        if (google == null || isBlank(google.getClientId()) || isBlank(google.getClientSecret())) {
            throw new IllegalStateException("GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET must be set");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank() || s.startsWith("${");
    }
}
