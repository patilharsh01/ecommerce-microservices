package com.harshalpatil.ecommerce.product.service;

import com.harshalpatil.ecommerce.common.event.OrderItemDto;
import com.harshalpatil.ecommerce.product.entity.Product;
import com.harshalpatil.ecommerce.product.exception.InsufficientStockException;
import com.harshalpatil.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reserves stock for every line item of an order in a single transaction.
 * Each Product carries an @Version column, so if two orders race for the
 * last units, one of them hits an OptimisticLockingFailureException and is
 * retried automatically instead of silently overselling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockReservationService {

    private final ProductRepository productRepository;

    @Retryable(
            retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @CacheEvict(value = "products", allEntries = true)
    @Transactional
    public void reserve(List<OrderItemDto> items) {
        for (OrderItemDto item : items) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new InsufficientStockException(item.getProductId(), item.getQuantity(), 0));

            if (product.getStockQuantity() < item.getQuantity()) {
                throw new InsufficientStockException(item.getProductId(), item.getQuantity(), product.getStockQuantity());
            }
        }

        // Second pass only runs once every item has already been validated above,
        // so a failure here means stock changed between checks — the @Retryable
        // annotation re-runs the whole reservation from a fresh read.
        for (OrderItemDto item : items) {
            Product product = productRepository.findById(item.getProductId()).orElseThrow();
            product.setStockQuantity(product.getStockQuantity() - item.getQuantity());
            productRepository.save(product);
        }

        log.info("Reserved stock for {} line item(s)", items.size());
    }
}
