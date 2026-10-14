package com.harshalpatil.ecommerce.product.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Row-locks the products, always in ascending id order. Two orders touching the same
     * products therefore queue up instead of deadlocking (A locks 1 then waits for 2 while
     * B holds 2 and waits for 1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /** {@code query} is matched against name and SKU; pass an empty string to match everything. */
    @Query("""
            select p from Product p
            where (:includeInactive = true or p.active = true)
              and (lower(p.name) like lower(concat('%', :query, '%'))
                   or lower(p.sku) like lower(concat('%', :query, '%')))
            """)
    Page<Product> search(@Param("query") String query, @Param("includeInactive") boolean includeInactive,
                         Pageable pageable);

    boolean existsBySku(String sku);
}
