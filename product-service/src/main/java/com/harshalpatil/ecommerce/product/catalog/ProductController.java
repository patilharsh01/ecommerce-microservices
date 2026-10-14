package com.harshalpatil.ecommerce.product.catalog;

import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Catalog")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "Browse the catalog (anonymous allowed). Admins may include inactive products.")
    public PageResponse<ProductResponse> search(@RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "false") boolean includeInactive,
                                                @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
                                                Optional<AuthenticatedUser> user) {
        boolean showInactive = includeInactive && user.map(AuthenticatedUser::isAdmin).orElse(false);
        return productService.search(q, showInactive, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @GetMapping("/lookup")
    @Operation(summary = "Fetch several products at once; unknown ids are omitted from the result")
    public List<ProductResponse> lookup(@RequestParam @Size(min = 1, max = 100) Set<Long> ids) {
        return productService.lookup(ids);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request, AuthenticatedUser user) {
        user.requireAdmin();
        return productService.create(request);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request,
                                  AuthenticatedUser user) {
        user.requireAdmin();
        return productService.update(id, request);
    }

    @PostMapping("/{id}/stock-adjustments")
    public ProductResponse adjustStock(@PathVariable Long id, @Valid @RequestBody StockAdjustmentRequest request,
                                       AuthenticatedUser user) {
        user.requireAdmin();
        return productService.adjustStock(id, request.delta());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a product (soft delete)")
    public void deactivate(@PathVariable Long id, AuthenticatedUser user) {
        user.requireAdmin();
        productService.deactivate(id);
    }
}
