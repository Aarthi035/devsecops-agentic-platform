package com.devsecops.productservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.devsecops.productservice.domain.Product;
import com.devsecops.productservice.error.ResourceNotFoundException;
import com.devsecops.productservice.repository.ProductRepository;
import com.devsecops.productservice.service.ProductService;
import com.devsecops.productservice.web.dto.ProductRequest;
import com.devsecops.productservice.web.dto.ProductResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link ProductService} with a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void findAll_mapsEntitiesToResponses() {
        when(productRepository.findAll()).thenReturn(List.of(
                new Product("Wireless Mouse", "Ergonomic wireless mouse", new BigDecimal("24.99"), 120, "Electronics")
        ));

        List<ProductResponse> products = productService.findAll();

        assertThat(products).hasSize(1);
        assertThat(products.get(0).name()).isEqualTo("Wireless Mouse");
        assertThat(products.get(0).category()).isEqualTo("Electronics");
    }

    @Test
    void findById_returnsProduct() {
        Product product = new Product("Clean Code", "Agile software craftsmanship",
                new BigDecimal("39.95"), 30, "Books");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.findById(1L);

        assertThat(response.name()).isEqualTo("Clean Code");
        assertThat(response.price()).isEqualByComparingTo("39.95");
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void create_trimsNameAndCategoryAndSaves() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.create(new ProductRequest(
                "  USB-C Cable  ", "1 m braided cable", new BigDecimal("12.00"), 75, "  Electronics  "));

        assertThat(response.name()).isEqualTo("USB-C Cable");
        assertThat(response.category()).isEqualTo("Electronics");
        assertThat(response.stock()).isEqualTo(75);
        verify(productRepository, times(1)).save(any(Product.class));
    }

    @Test
    void update_changesFieldsOfExistingProduct() {
        Product product = new Product("Old Name", "Old description",
                new BigDecimal("10.00"), 5, "Home");
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        ProductResponse response = productService.update(7L, new ProductRequest(
                "New Name", "New description", new BigDecimal("19.99"), 42, "Grocery"));

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.price()).isEqualByComparingTo("19.99");
        assertThat(response.stock()).isEqualTo(42);
        assertThat(response.category()).isEqualTo("Grocery");
    }

    @Test
    void delete_removesExistingProduct() {
        Product product = new Product("To Delete", "Temporary product",
                new BigDecimal("5.00"), 1, "Books");
        when(productRepository.findById(3L)).thenReturn(Optional.of(product));

        productService.delete(3L);

        verify(productRepository, times(1)).delete(product);
    }

    @Test
    void delete_throwsNotFoundWhenMissing() {
        when(productRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.delete(42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
