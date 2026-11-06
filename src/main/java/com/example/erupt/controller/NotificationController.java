package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.Result;
import com.example.erupt.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内通知接口：收件箱 / 未读角标 / 已读 / 删除。
 *
 * <p>整组挂在 /api/notifications 下，走 MenuAccessInterceptor 的通用规则：
 * URI 第二段 notifications → 校验菜单权限 notifications（菜单 m25）。
 * 所有查询都按当前登录人过滤，通知是「只发给接收人自己」的数据。</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 通知分页：type / readFlag 可空 = 全部；未读排最前 */
    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String readFlag) {
        return ResponseEntity.ok(Result.success(
                notificationService.list(AuthContext.currentUserId(), type, readFlag, page, pageSize)));
    }

    /** 未读数：铃铛角标，轮询与 WS 推送都用它兜底 */
    @GetMapping("/unread-count")
    public ResponseEntity<Result<Long>> unreadCount() {
        return ResponseEntity.ok(Result.success(notificationService.unreadCount(AuthContext.currentUserId())));
    }

    /** 标记单条已读 */
    @PutMapping("/{id}/read")
    public ResponseEntity<Result<Void>> markRead(@PathVariable String id) {
        notificationService.markRead(AuthContext.currentUserId(), id);
        return ResponseEntity.ok(Result.success(null, "已标记已读"));
    }

    /** 全部已读 */
    @PutMapping("/read-all")
    public ResponseEntity<Result<Integer>> markAllRead() {
        int updated = notificationService.markAllRead(AuthContext.currentUserId());
        return ResponseEntity.ok(Result.success(updated, updated + " 条通知已标记已读"));
    }

    /** 软删除单条通知 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable String id) {
        notificationService.delete(AuthContext.currentUserId(), id);
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }
}
