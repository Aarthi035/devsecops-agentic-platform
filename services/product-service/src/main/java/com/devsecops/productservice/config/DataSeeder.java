package com.devsecops.productservice.config;

import com.devsecops.productservice.domain.Product;
import com.devsecops.productservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds demo products at startup so the API returns data immediately.
 * Disable with APP_SEED_ENABLED=false (integration tests do this).
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ProductRepository productRepository;

    public DataSeeder(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Products already present, skipping seed");
            return;
        }
        productRepository.saveAll(List.of(
                new Product("Wireless Mouse", "Ergonomic 2.4 GHz wireless mouse with adjustable DPI",
                        new BigDecimal("24.99"), 120, "Electronics"),
                new Product("Mechanical Keyboard", "Tenkeyless mechanical keyboard with blue switches",
                        new BigDecimal("79.50"), 45, "Electronics"),
                new Product("Clean Code", "A handbook of agile software craftsmanship",
                        new BigDecimal("39.95"), 30, "Books"),
                new Product("Spring in Action", "Comprehensive guide to building Spring applications",
                        new BigDecimal("49.99"), 25, "Books"),
                new Product("Ceramic Coffee Mug", "350 ml microwave safe ceramic mug",
                        new BigDecimal("9.75"), 200, "Home"),
                new Product("Organic Green Tea", "Premium loose leaf green tea, 250 g pack",
                        new BigDecimal("12.40"), 80, "Grocery")
        ));
        log.info("Seeded {} demo products", productRepository.count());
    }
}
