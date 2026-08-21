package com.corebank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI coreBankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CoreBank Lite API")
                        .version("v1")
                        .description("""
                                Retail banking accounts, balances and double-entry transactions.

                                **Getting started**
                                1. `POST /api/v1/auth/login` with a staff username and password to obtain a bearer token.
                                2. Click **Authorize** above and paste the token.
                                3. Create a customer, open an account, then deposit, withdraw or transfer.

                                **Idempotency** &mdash; every money-moving endpoint requires an `Idempotency-Key`
                                header. Replaying the same key with the same body returns the original result
                                instead of moving money twice; replaying it with a different body returns `409`.
                                """)
                        .contact(new Contact().name("CoreBank Lite"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/").description("This server")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT issued by POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
