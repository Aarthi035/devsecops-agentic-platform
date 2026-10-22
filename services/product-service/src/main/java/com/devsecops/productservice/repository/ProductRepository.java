package com.devsecops.productservice.repository;

import com.devsecops.productservice.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategory(String category);

    Optional<Product> findByName(String name);

    boolean existsByName(String name);
}
