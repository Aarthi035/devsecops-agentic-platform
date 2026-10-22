package com.devsecops.orderservice.client;

import com.devsecops.orderservice.client.dto.ProductDto;
import com.devsecops.orderservice.error.DownstreamServiceException;
import com.devsecops.orderservice.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProductClient {

    private static final Logger log = LoggerFactory.getLogger(ProductClient.class);

    private final RestClient restClient;

    public ProductClient(@Qualifier("productRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ProductDto fetchProduct(Long productId) {
        try {
            ProductDto product = restClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .body(ProductDto.class);
            if (product == null) {
                throw new DownstreamServiceException("product-service returned an empty body for product " + productId, null);
            }
            return product;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Product " + productId + " was not found in product-service");
        } catch (RestClientException ex) {
            log.error("Call to product-service failed for product {}: {}", productId, ex.getMessage());
            throw new DownstreamServiceException("product-service is unavailable: " + ex.getMessage(), ex);
        }
    }
}
