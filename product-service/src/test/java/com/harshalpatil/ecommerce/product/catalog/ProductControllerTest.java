package com.harshalpatil.ecommerce.product.catalog;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import com.harshalpatil.ecommerce.platform.web.PlatformWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@ImportAutoConfiguration(PlatformWebAutoConfiguration.class)
class ProductControllerTest {

    private static final String VALID_PRODUCT = """
            {"sku":"KB-01","name":"Keyboard","description":"75%","price":4999.00,"stockQuantity":10}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductService productService;

    @Test
    void anyoneCanBrowseTheCatalog() throws Exception {
        when(productService.search(any(), anyBoolean(), any()))
                .thenReturn(new PageResponse<>(List.of(sample()), 0, 20, 1, 1));

        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("KB-01"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void inactiveProductsAreOnlyShownToAdmins() throws Exception {
        when(productService.search(any(), anyBoolean(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/products").param("includeInactive", "true")
                .header(IdentityHeaders.USER_ID, "5").header(IdentityHeaders.USER_ROLES, "CUSTOMER"));
        verify(productService).search(any(), eq(false), any());

        mvc.perform(get("/api/products").param("includeInactive", "true")
                .header(IdentityHeaders.USER_ID, "1").header(IdentityHeaders.USER_ROLES, "ADMIN"));
        verify(productService).search(any(), eq(true), any());
    }

    @Test
    void anonymousCallersCannotCreateProducts() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isUnauthorized());
        verify(productService, never()).create(any());
    }

    @Test
    void customersCannotCreateProducts() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT)
                        .header(IdentityHeaders.USER_ID, "5").header(IdentityHeaders.USER_ROLES, "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(productService, never()).create(any());
    }

    @Test
    void adminsCanCreateProducts() throws Exception {
        when(productService.create(any())).thenReturn(sample());

        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT)
                        .header(IdentityHeaders.USER_ID, "1").header(IdentityHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void invalidProductsAreRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"bad sku","name":"","price":0,"stockQuantity":-1}""")
                        .header(IdentityHeaders.USER_ID, "1").header(IdentityHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void lookupIsCappedAtOneHundredIds() throws Exception {
        String tooMany = java.util.stream.LongStream.rangeClosed(1, 101).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(get("/api/products/lookup").param("ids", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private static ProductResponse sample() {
        return new ProductResponse(1L, "KB-01", "Keyboard", "75%", new BigDecimal("4999.00"), 10, true, 0);
    }
}
