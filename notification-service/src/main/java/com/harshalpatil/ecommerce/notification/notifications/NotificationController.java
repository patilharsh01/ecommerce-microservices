package com.harshalpatil.ecommerce.notification.notifications;

import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
class NotificationController {

    private final NotificationService notifications;

    @GetMapping
    @Operation(summary = "The caller's notifications, newest first; admins may filter by customerId")
    PageResponse<NotificationResponse> list(@RequestParam(required = false) Long orderId,
                                            @RequestParam(required = false) Long customerId,
                                            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable,
                                            AuthenticatedUser user) {
        return notifications.list(user, customerId, orderId, pageable);
    }
}
