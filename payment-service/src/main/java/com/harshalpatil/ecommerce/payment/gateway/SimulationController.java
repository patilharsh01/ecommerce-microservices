package com.harshalpatil.ecommerce.payment.gateway;

import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/simulation")
@RequiredArgsConstructor
@Tag(name = "Failure injection")
class SimulationController {

    private final SimulatedPaymentGateway gateway;

    @GetMapping
    SimulationSettings get(AuthenticatedUser user) {
        user.requireAdmin();
        return gateway.settings();
    }

    @PutMapping
    @Operation(summary = "Change how the simulated gateway behaves (decline everything, add latency, ...)")
    SimulationSettings update(@Valid @RequestBody SimulationSettings settings, AuthenticatedUser user) {
        user.requireAdmin();
        return gateway.update(settings);
    }
}
