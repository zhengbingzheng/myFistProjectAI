-- ============================================================================
-- 第 3 步：任务到期提醒（type=task）+ AI 读库审计快照（t_agent_data_audit）
--
-- 两件事、一次迁移：
-- 1. t_notification 加 biz_id：提醒去重的锚点。
--    定时任务每天扫甘特任务，「同一任务 3 天内提醒过就不再发」的判断
--    需要能按「业务对象」反查通知——biz_path 只存路由、title 是给人看的文本，
--    都不适合做精确匹配；只有 biz_id = 任务 id 是稳定锚点。
-- 2. t_agent_data_audit：AI 读库的审计快照，谁问了什么、AI 生成了什么 SQL、
--    结果多少行、成功与否。只增不改（无 update_time / del_flag，无删除接口）——
--    与 t_operation_log 同一原则：能被 API 删掉的审计就不是审计。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) 通知表加 biz_id
-- ----------------------------------------------------------------------------
ALTER TABLE `t_notification`
    ADD COLUMN `biz_id` varchar(64) DEFAULT NULL COMMENT '业务对象 id（如甘特任务 id），用于事件去重与溯源' AFTER `type`,
    ADD INDEX `idx_biz_type` (`biz_id`, `type`);

-- ----------------------------------------------------------------------------
-- 2) AI 读库审计快照
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_data_audit` (
    `id`               varchar(32)  NOT NULL COMMENT '主键，UUID 去横线',
    `user_id`          varchar(32)  NOT NULL COMMENT '提问人，t_user.id',
    `question`         varchar(500) NOT NULL COMMENT '用户的自然语言问题（原文快照）',
    `sql_text`         text         DEFAULT NULL COMMENT 'AI 生成并实际执行的 SQL；生成阶段失败则为空',
    `row_count`        int(11)      DEFAULT NULL COMMENT '返回行数；失败则为空',
    `elapsed_gen_ms`   bigint(20)   DEFAULT NULL COMMENT 'SQL 生成耗时（含上游往返）',
    `elapsed_query_ms` bigint(20)   DEFAULT NULL COMMENT 'SQL 执行耗时',
    `success`          tinyint(4)   NOT NULL DEFAULT 0 COMMENT '0 失败 / 1 成功',
    `error_msg`        varchar(500) DEFAULT NULL COMMENT '失败原因（校验拦截 / 执行报错），成功为空',
    `create_time`      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 查询形状：按人追溯 / 按时间浏览
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 读库审计快照（只增不改）';
