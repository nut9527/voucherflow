package com.example.voucherflow.notification;

import com.example.voucherflow.common.ApiResponse;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前用户的站内通知游标分页查询。 */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<PageResult<NotificationService.Summary>> page(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notifications.ownedPage(new PageQuery(afterId, size)));
    }
}
