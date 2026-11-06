-- ============================================================================
-- 通知中心（t_notification）
--
-- 一行 = 一条发给某个用户的站内通知。设计要点：
-- 1. 通知是「推给接收人」的数据，归属字段是 user_id（接收人），不是 create_by——
--    发起方（系统 / 事件源模块）不落 create_by，与「审计由 t_operation_log 负责」的分工一致；
-- 2. type 是事件来源大类，前端按它渲染图标与页签：agent（智能体回复完成）/ task（任务提醒，第 3 步接入）/ system（系统公告）；
-- 3. biz_path 存前端路由（如 /agent），点通知直接跳到事件发生地，不存后端 URL；
-- 4. 已读用 read_flag + read_time，未读角标走 (user_id, read_flag) 索引，O(1) 计数；
-- 5. 软删除：用户在列表里「删掉」一条通知只是 del_flag=1，事件溯源仍在库里。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `t_notification` (
    `id`          varchar(32)  NOT NULL COMMENT '主键，UUID 去横线',
    `user_id`     varchar(32)  NOT NULL COMMENT '接收人，t_user.id',
    `title`       varchar(200) NOT NULL COMMENT '标题（列表只显示标题 + 时间）',
    `content`     varchar(1000) DEFAULT NULL COMMENT '正文补充说明，可空',
    `type`        varchar(20)  NOT NULL DEFAULT 'system' COMMENT '类型：agent 智能体 / task 任务 / system 系统',
    `biz_id`      varchar(64)  DEFAULT NULL COMMENT '业务对象 id（如甘特任务 id），用于事件去重与溯源',
    `biz_path`    varchar(200) DEFAULT NULL COMMENT '前端路由，点击通知跳转；空表示不可跳转',
    `read_flag`   char(1)      NOT NULL DEFAULT '0' COMMENT '已读标记：0 未读 / 1 已读',
    `read_time`   datetime      DEFAULT NULL COMMENT '已读时间',
    `del_flag`    char(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删除',
    `create_time` datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 未读角标与列表都是「按接收人查」：联合索引覆盖过滤 + 排序，避免 filesort
    KEY `idx_user_read_time` (`user_id`, `read_flag`, `create_time`),
    -- 事件去重：定时任务按「业务对象 + 类型」反查最近是否已提醒过
    KEY `idx_biz_type` (`biz_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';

-- ----------------------------------------------------------------------------
-- 菜单：通知中心（m25，顶级，编号顺延——m24 是时间规划）
-- permission = notifications，与 NotificationController 的 /api/notifications 前缀一致，
-- MenuAccessInterceptor 按 URI 第二段自动鉴权，无需改 Java 权限代码。
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m25', '0', '通知中心', 'notification', 2, '/notifications', 'notification/NotificationView.vue',
     'Bell', 87, 1, 'notifications', '0', 'init', NOW(), NOW());

INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m25', 'r_super', 'm25', 'init', NOW()),
    ('rm_u_m25', 'r_user', 'm25', 'init', NOW());
