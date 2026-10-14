package com.harshalpatil.ecommerce.product.catalog;

import com.harshalpatil.ecommerce.platform.web.BusinessRuleException;
import com.harshalpatil.ecommerce.platform.web.ConflictException;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Catalog reads are cached per product in Redis. The cache is transaction-aware: evictions
 * are applied after the surrounding transaction commits, so a reader can't re-cache the old
 * value in the gap between "evicted" and "committed".
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    public static final String CACHE = "products";

    private final ProductRepository products;

    public PageResponse<ProductResponse> search(String query, boolean includeInactive, Pageable pageable) {
        String normalized = query == null ? "" : query.trim();
        return PageResponse.of(products.search(normalized, includeInactive, pageable), ProductResponse::from);
    }

    @Cacheable(cacheNames = CACHE, key = "#id")
    public ProductResponse get(Long id) {
        return ProductResponse.from(find(id));
    }

    /** Batch read used by order-service to price an order in one round trip. Unknown ids are simply absent. */
    public List<ProductResponse> lookup(Collection<Long> ids) {
        return products.findAllById(ids).stream().map(ProductResponse::from).toList();
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (products.existsBySku(request.sku())) {
            throw new ConflictException("SKU_TAKEN", "A product with SKU " + request.sku() + " already exists");
        }
        Product product = new Product(request.sku(), request.name(), request.description(), request.price(),
                request.stockQuantity());
        return ProductResponse.from(products.saveAndFlush(product));
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#id")
    public ProductResponse update(Long id, UpdateProductRequest request) {
        Product product = find(id);
        if (!Objects.equals(product.getVersion(), request.version())) {
            throw new ConflictException("STALE_VERSION", "Product " + id + " changed since you loaded it (version "
                    + request.version() + ", now " + product.getVersion() + "); reload and retry");
        }
        product.update(request.name(), request.description(), request.price(), request.stockQuantity(),
                request.active());
        return ProductResponse.from(products.saveAndFlush(product));
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#id")
    public ProductResponse adjustStock(Long id, int delta) {
        Product product = products.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Product", id));
        if (!product.adjustStock(delta)) {
            throw new BusinessRuleException("NEGATIVE_STOCK", "Adjusting product " + id + " by " + delta
                    + " would leave " + (product.getStockQuantity() + delta) + " units");
        }
        return ProductResponse.from(products.saveAndFlush(product));
    }

    /** Products are never hard-deleted: past orders and reservations still reference them. */
    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#id")
    public void deactivate(Long id) {
        find(id).deactivate();
    }

    private Product find(Long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }
}
