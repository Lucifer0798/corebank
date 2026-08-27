package com.corebank.config;

import java.net.URISyntaxException;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Building this client never itself talks to OpenSearch -- like {@code KafkaTemplate} and the
 * Redis connection factory elsewhere in this application, it is a lazy wrapper, so the bean is
 * created successfully even if OpenSearch is unreachable at startup. See
 * {@code SearchIndexInitializer} for the one place that does make a network call at startup, and
 * why that call is not allowed to fail the application context.
 */
@Configuration
public class OpenSearchConfig {

    @Bean
    public OpenSearchClient openSearchClient(CoreBankProperties properties) throws URISyntaxException {
        RestClient restClient = RestClient.builder(HttpHost.create(properties.search().opensearchUri())).build();
        OpenSearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }
}
