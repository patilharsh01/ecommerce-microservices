package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.platform.web.DependencyUnavailableException;
import com.harshalpatil.ecommerce.platform.web.PlatformWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@ImportAutoConfiguration(PlatformWebAutoConfiguration.class)
class OrderControllerTest {

    private static final String ORDER = """
            {"items":[{"productId":1,"quantity":2}]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orderService;

    @Test
    void newOrderIsCreatedWithALocation() throws Exception {
        when(orderService.place(any(), eq("0f8fad5b-d9cb-469f-a165-70867728950e"), any()))
                .thenReturn(new OrderService.Placement(sample(), false));

        mvc.perform(asCustomer(post("/api/orders")).header("Idempotency-Key", "0f8fad5b-d9cb-469f-a165-70867728950e")
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/100"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void replayedOrderIsOkAndFlagged() throws Exception {
        when(orderService.place(any(), any(), any())).thenReturn(new OrderService.Placement(sample(), true));

        mvc.perform(asCustomer(post("/api/orders")).header("Idempotency-Key", "0f8fad5b-d9cb-469f-a165-70867728950e")
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(asCustomer(post("/api/orders")).header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).place(any(), any(), any());
    }

    @Test
    void emptyOrdersAndSillyQuantitiesAreRejected() throws Exception {
        mvc.perform(asCustomer(post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(asCustomer(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordersRequireAnAuthenticatedCustomer() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void catalogOutageSurfacesAs503() throws Exception {
        when(orderService.place(any(), any(), any()))
                .thenThrow(new DependencyUnavailableException("The product catalog", new RuntimeException("down")));

        mvc.perform(asCustomer(post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return request.header(IdentityHeaders.USER_ID, "7").header(IdentityHeaders.USER_ROLES, "CUSTOMER");
    }

    private static OrderResponse sample() {
        return new OrderResponse(100L, 7L, OrderStatus.PENDING, new BigDecimal("2000.00"), 2, null, null, null,
                Instant.now().plusSeconds(30), Instant.now(), Instant.now(), null, List.of(), List.of());
    }
}
