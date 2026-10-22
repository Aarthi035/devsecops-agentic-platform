package com.devsecops.userservice.config;

import com.devsecops.userservice.domain.User;
import com.devsecops.userservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds demo users at startup so the API returns data immediately.
 * Disable with APP_SEED_ENABLED=false (integration tests do this).
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;

    public DataSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Users already present, skipping seed");
            return;
        }
        userRepository.saveAll(List.of(
                new User("Aarthi Kumar", "aarthi@example.com", "ADMIN"),
                new User("Ravi Menon", "ravi@example.com", "USER"),
                new User("Priya Sharma", "priya@example.com", "USER"),
                new User("Dev Anand", "dev@example.com", "SUPPORT"),
                new User("Meera Nair", "meera@example.com", "USER")
        ));
        log.info("Seeded {} demo users", userRepository.count());
    }
}
