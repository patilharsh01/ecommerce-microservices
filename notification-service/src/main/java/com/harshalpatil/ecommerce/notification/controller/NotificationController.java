package com.harshalpatil.ecommerce.notification.controller;

import com.harshalpatil.ecommerce.notification.model.Notification;
import com.harshalpatil.ecommerce.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/api/notifications")
    public ResponseEntity<List<Notification>> recent(@RequestParam(required = false) Long customerId) {
        return ResponseEntity.ok(notificationService.recent(customerId));
    }
}
