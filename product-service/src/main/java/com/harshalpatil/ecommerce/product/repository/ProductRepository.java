package com.harshalpatil.ecommerce.product.repository;

import com.harshalpatil.ecommerce.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
