package com.corebank.auth.service;

import com.corebank.config.CoreBankProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Mints the short-lived access tokens the API accepts. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final CoreBankProperties properties;

    public TokenService(JwtEncoder encoder, CoreBankProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(AppUserPrincipal principal) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.security().jwt().accessTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.security().jwt().issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(principal.username())
                .claim("uid", principal.userId().toString())
                .claim("roles", principal.roleNames());

        // Present only for self-service logins; used to authorise access to own accounts.
        if (principal.customerId() != null) {
            claims.claim("customerId", principal.customerId().toString());
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, issuedAt, expiresAt);
    }

    public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {

        public long expiresInSeconds() {
            return Math.max(0, expiresAt.getEpochSecond() - issuedAt.getEpochSecond());
        }
    }
}
