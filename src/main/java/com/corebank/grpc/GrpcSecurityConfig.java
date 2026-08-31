package com.corebank.grpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.grpc.server.security.SecurityContextServerInterceptor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * gRPC-side security, the counterpart to {@code SecurityConfig}'s HTTP filter chain.
 *
 * <p>This replaces a hand-written {@code ServerInterceptor} that decoded the bearer token
 * itself. Spring Boot already auto-configures a JWT authentication interceptor for gRPC from the
 * same {@code spring.security.oauth2.resourceserver.jwt.*} properties the REST surface uses
 * (see {@code GrpcServerOAuth2ResourceServerAutoConfiguration}), so the custom one was
 * reimplementing framework behaviour -- the worst place to do that being security. Declaring
 * this bean overrides that autoconfiguration, which is conditional on one not being present, and
 * keeps the token handling in the framework's hands while stating the authorization rules
 * explicitly.
 *
 * <p>The {@code JwtAuthenticationConverter} has to be passed in explicitly: unlike the
 * {@code JwtDecoder}, it is <em>not</em> discovered from the application context here. Left to
 * its default, gRPC calls authenticate successfully but arrive with none of Keycloak's realm
 * roles, because the default converter only understands flat {@code scope}/{@code scp} claims --
 * Keycloak nests them under {@code realm_access.roles}. A TELLER token then reaches
 * {@code AccountSecurity} with no {@code ROLE_TELLER} authority and is refused
 * {@code PERMISSION_DENIED} on its own account. Verified against a real token and a real server;
 * passing {@code SecurityConfig}'s own converter bean is what makes a token grant identical
 * authorities on both surfaces.
 */
@Configuration
public class GrpcSecurityConfig {

    /**
     * Health and reflection are public for the same reasons {@code /actuator/health} is in
     * {@code SecurityConfig.PUBLIC_PATHS}: a Kubernetes probe has no Keycloak token to present,
     * and reflection exposes only the service schema already published in
     * {@code src/main/proto/corebank.proto}, never account or customer data.
     */
    private static final String[] PUBLIC_SERVICES = {
            "grpc.health.v1.Health/*",
            "grpc.reflection.v1.ServerReflection/*",
            "grpc.reflection.v1alpha.ServerReflection/*"
    };

    /**
     * {@code @GlobalServerInterceptor} is load-bearing, not decoration. Declaring this bean
     * switches off Boot's own auto-configured equivalent (it is
     * {@code @ConditionalOnMissingBean}), and Boot's carries this annotation. Without it here the
     * replacement is a bean nothing ever applies: authentication silently stops happening
     * entirely, and every call arrives anonymous -- which looks like an authorization bug
     * (staff refused their own account) rather than the authentication bug it is.
     */
    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor grpcAuthenticationInterceptor(
            GrpcSecurity grpc, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return grpc
                .authorizeRequests(requests -> requests
                        .methods(PUBLIC_SERVICES).permitAll()
                        // Everything else needs a valid token. Which role may call what is then
                        // decided per RPC, by the same AccountSecurity bean and @PreAuthorize
                        // expressions the REST controllers use -- not restated here, so the two
                        // surfaces cannot disagree.
                        .allRequests().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    /**
     * Copies the authenticated principal out of the gRPC {@link io.grpc.Context} the interceptor
     * above populates and into Spring Security's thread-local {@code SecurityContextHolder}.
     *
     * <p>Without it, authentication succeeds and then nothing downstream can see it: every
     * {@code @PreAuthorize} fails as {@code UNAUTHENTICATED}, and {@code AccountSecurity} is
     * handed a null {@code Authentication} and refuses a staff token
     * {@code PERMISSION_DENIED} on its own account. Both symptoms were observed against a real
     * server before this bean existed.
     *
     * <p>{@code @GlobalServerInterceptor} is what actually applies it to every call --
     * {@code @Component} alone is not enough for Spring gRPC to pick a {@code ServerInterceptor}
     * up, which is a quiet way for an interceptor to look wired and never run.
     */
    @Bean
    @GlobalServerInterceptor
    SecurityContextServerInterceptor grpcSecurityContextInterceptor() {
        return new SecurityContextServerInterceptor();
    }
}
