package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 通知列表项。
 *
 * <p>只暴露前端需要的字段：归属（userId）不回传——能进这个接口的用户就是接收人本人，
 * 回传归属字段反而给前端「比对他人通知」的错误暗示。</p>
 */
public record NotificationVO(
        String id,
        String title,
        String content,
        String type,
        String bizPath,
        String readFlag,
        LocalDateTime readTime,
        LocalDateTime createTime) {
}
