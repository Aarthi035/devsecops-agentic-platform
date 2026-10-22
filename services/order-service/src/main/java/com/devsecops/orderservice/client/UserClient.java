package com.devsecops.orderservice.client;

import com.devsecops.orderservice.client.dto.UserDto;
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
public class UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClient.class);

    private final RestClient restClient;

    public UserClient(@Qualifier("userRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public UserDto fetchUser(Long userId) {
        try {
            UserDto user = restClient.get()
                    .uri("/api/users/{id}", userId)
                    .retrieve()
                    .body(UserDto.class);
            if (user == null) {
                throw new DownstreamServiceException("user-service returned an empty body for user " + userId, null);
            }
            return user;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("User " + userId + " was not found in user-service");
        } catch (RestClientException ex) {
            log.error("Call to user-service failed for user {}: {}", userId, ex.getMessage());
            throw new DownstreamServiceException("user-service is unavailable: " + ex.getMessage(), ex);
        }
    }
}
