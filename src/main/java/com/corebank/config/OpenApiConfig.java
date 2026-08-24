package com.corebank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String OAUTH2_SCHEME = "keycloak";
    static final String BEARER_SCHEME = "bearerAuth";

    @Value("${corebank.oidc.authorize-uri:http://localhost:8081/realms/corebank/protocol/openid-connect/auth}")
    private String authorizeUri;

    @Value("${corebank.oidc.token-uri:http://localhost:8081/realms/corebank/protocol/openid-connect/token}")
    private String tokenUri;

    @Bean
    public OpenAPI coreBankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CoreBank Lite API")
                        .version("v1")
                        .description("""
                                Retail banking accounts, balances and double-entry transactions.

                                **Getting started** &mdash; click **Authorize** above. Keycloak's login page opens in
                                a popup; sign in as `admin` / `ChangeMe#2025!`, `teller1` / `Teller#2025`, or
                                `asha` / `Customer#2025` (see `keycloak/corebank-realm.json`). Swagger UI then
                                attaches the resulting token to every request automatically. A raw bearer token can
                                be pasted into the second scheme instead, if you already have one.

                                **Idempotency** &mdash; every money-moving endpoint requires an `Idempotency-Key`
                                header. Replaying the same key with the same body returns the original result
                                instead of moving money twice; replaying it with a different body returns `409`.
                                """)
                        .contact(new Contact().name("CoreBank Lite"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/").description("This server")))
                .components(new Components()
                        .addSecuritySchemes(OAUTH2_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Authorization Code + PKCE against Keycloak")
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(authorizeUri)
                                                .tokenUrl(tokenUri)
                                                .scopes(new Scopes().addString("openid", "OpenID Connect")))))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste an existing Keycloak-issued access token")))
                .addSecurityItem(new SecurityRequirement().addList(OAUTH2_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
