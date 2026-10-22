package com.finpath.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Two filter chains.
 * <ol>
 *   <li>Google sign-in ({@code /oauth2/authorization/**}, {@code /login/oauth2/code/**}): Spring's
 *       oauth2Login as a confidential client. The only place a session exists, and only for the
 *       few seconds between the redirect to Google and the callback.</li>
 *   <li>Everything else: a stateless bearer-JWT resource server. Health and the token endpoints
 *       are public; every other path needs {@code Authorization: Bearer <jwt>}. No cookies are
 *       read, so CSRF protection is off for this chain only.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
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
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint();
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/token", "/auth/refresh").permitAll()
                        .requestMatchers("/error").permitAll()
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

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // JwtTimestampValidator accepts a token with no exp at all, so require it explicitly.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(JwtIssuer.ISSUER),
                subjectIsUserId()));
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
