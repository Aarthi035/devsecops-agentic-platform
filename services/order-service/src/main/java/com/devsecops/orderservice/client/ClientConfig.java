package com.devsecops.orderservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient beans for the two downstream services.
 * Base URLs come from configuration so Kubernetes can point them at Service DNS names.
 */
@Configuration
public class ClientConfig {

    @Bean
    public RestClient userRestClient(@Value("${clients.user-service.base-url}") String baseUrl) {
        return buildClient(baseUrl);
    }

    @Bean
    public RestClient productRestClient(@Value("${clients.product-service.base-url}") String baseUrl) {
        return buildClient(baseUrl);
    }

    private RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }
}
