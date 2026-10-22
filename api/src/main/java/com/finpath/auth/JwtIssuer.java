package com.finpath.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Mints FinPath access tokens: HS256, iss=finpath, sub=users.id, short-lived. */
@Component
public class JwtIssuer {

    public static final String ISSUER = "finpath";

    public record AccessToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public JwtIssuer(JwtEncoder encoder, AuthProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    JwtIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issue(UUID userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AccessToken(value, properties.accessTokenTtl().toSeconds());
    }
}
