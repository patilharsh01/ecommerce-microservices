package com.harshalpatil.ecommerce.product.inventory;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What inventory holds for one order. Keyed by order id, so it also acts as the record of
 * "have we already dealt with this order", which makes reserve and release safe to receive
 * twice or out of order.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation implements Persistable<Long> {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(length = 500)
    private String reason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "stock_reservation_lines", joinColumns = @JoinColumn(name = "order_id"))
    private List<ReservedLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    private StockReservation(Long orderId, ReservationStatus status, String reason, List<ReservedLine> lines) {
        this.orderId = orderId;
        this.status = status;
        this.reason = reason;
        this.lines = new ArrayList<>(lines);
    }

    static StockReservation reserved(Long orderId, Map<Long, Integer> quantities) {
        List<ReservedLine> lines = quantities.entrySet().stream()
                .map(entry -> new ReservedLine(entry.getKey(), entry.getValue()))
                .toList();
        return new StockReservation(orderId, ReservationStatus.RESERVED, null, lines);
    }

    static StockReservation rejected(Long orderId, String reason) {
        return new StockReservation(orderId, ReservationStatus.REJECTED, reason, List.of());
    }

    /** The release arrived before (or instead of) the reservation: remember it so a late reserve is refused. */
    static StockReservation releasedBeforeReserved(Long orderId, String reason) {
        return new StockReservation(orderId, ReservationStatus.RELEASED, reason, List.of());
    }

    void release(String reason) {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Reservation for order " + orderId + " is " + status + ", not RESERVED");
        }
        this.status = ReservationStatus.RELEASED;
        this.reason = reason;
    }

    @Override
    public Long getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
