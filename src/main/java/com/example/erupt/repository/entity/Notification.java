package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内通知实体，对应表 {@code t_notification}。
 *
 * <p>一行 = 一条推给某个用户的通知。事件源（智能体、任务提醒、系统公告）通过
 * {@code NotificationService.notify(...)} 写入并实时推送，接收人只读自己的。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    private String id;
    /** 接收人，t_user.id ——通知的归属字段（不是 create_by，发起方是系统/事件源模块） */
    private String userId;
    private String title;
    private String content;
    /** 类型：agent 智能体 / task 任务提醒 / system 系统公告 */
    private String type;
    /**
     * 业务对象 id（如甘特任务 id），可空。
     * 通知去重与溯源的锚点：biz_path 只存路由、title 是给人看的文本，
     * 都不适合精确匹配；定时任务按「biz_id + type」反查最近是否已提醒过。
     */
    private String bizId;
    /** 前端路由（如 /agent），点击通知跳转到事件发生地；空表示不可跳转 */
    private String bizPath;
    /** 已读标记：0 未读，1 已读 */
    private String readFlag;
    private LocalDateTime readTime;
    private String delFlag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
