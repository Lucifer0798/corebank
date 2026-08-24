package com.corebank.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.config.SecurityConfig.RealmRoleConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The full request-level tests inject a fake {@code Authentication} directly and never invoke
 * this converter, so its mapping of Keycloak's nested {@code realm_access.roles} claim onto
 * {@code ROLE_} authorities is checked here instead, against the token shape Keycloak actually
 * issues.
 */
class RealmRoleConverterTest {

    private final RealmRoleConverter converter = new RealmRoleConverter();

    @Test
    void mapsEachRealmRoleToAPrefixedAuthority() {
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of("ADMIN", "TELLER")));

        assertThat(converter.convert(jwt))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TELLER");
    }

    @Test
    void anEmptyRoleListYieldsNoAuthorities() {
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of()));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void aMissingRealmAccessClaimYieldsNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void aRealmAccessClaimWithoutARolesEntryYieldsNoAuthorities() {
        Jwt jwt = jwtWithRealmAccess(Map.of("someOtherKey", "value"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRealmAccess(Map<String, Object> realmAccess) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("someone")
                .claim("realm_access", realmAccess)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
