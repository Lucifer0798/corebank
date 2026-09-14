package com.corebank.config;

import java.net.URISyntaxException;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
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
        CoreBankProperties.Search search = properties.search();
        RestClient restClient = RestClient.builder(HttpHost.create(search.opensearchUri()))
                // Without these, SearchService's synchronous client.search() calls inherit
                // Apache HttpClient's own defaults: no response timeout at all, and a pool sized
                // for a handful of callers (10 per route) -- see CoreBankProperties.Search.
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(Timeout.ofMilliseconds(search.connectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(search.socketTimeout().toMillis())))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                            .create()
                            .setMaxConnPerRoute(search.maxConnections())
                            .setMaxConnTotal(search.maxConnections())
                            .build();
                    return httpClientBuilder.setConnectionManager(connectionManager);
                })
                .build();
        OpenSearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }
}
