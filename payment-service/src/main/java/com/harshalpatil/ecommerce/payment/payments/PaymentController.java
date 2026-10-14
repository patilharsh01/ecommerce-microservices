package com.harshalpatil.ecommerce.payment.payments;

import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
class PaymentController {

    private final PaymentService payments;

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Payment for one of the caller's orders (admins: any order)")
    PaymentResponse forOrder(@PathVariable Long orderId, AuthenticatedUser user) {
        return payments.findByOrder(orderId, user);
    }

    @GetMapping
    @Operation(summary = "All payments, newest first (admin only)")
    PageResponse<PaymentResponse> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable,
                                       AuthenticatedUser user) {
        user.requireAdmin();
        return payments.list(pageable);
    }
}
