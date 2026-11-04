-- ============================================================================
-- 系统操作日志模块表结构 + 菜单种子
--
-- 执行方式（务必带字符集，否则中文注释入库成乱码）：
--   mysql --default-character-set=utf8mb4 -uroot -p erupt-example < schema-operation-log.sql
--
-- 幂等：表用 CREATE TABLE IF NOT EXISTS；菜单与授权用 INSERT IGNORE，
--       可重复执行，不会覆盖已手工调整过的数据。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 系统操作日志
--
-- 记录 /api/** 请求的审计信息：谁（操作人/IP）、对哪个模块做了什么（模块/操作类型/URI）、
-- 结果如何（状态码/耗时）。由 OperationLogInterceptor 在请求结束后异步写入，
-- 不记录请求体（含密码等敏感字段），也不记录成功 GET（量太大且无审计价值）。
-- 日志只增不改：无 update_by / del_flag，查询接口也只读。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_operation_log` (
    `id`             varchar(64)  NOT NULL COMMENT '主键，字符串（64 位），应用层生成 UUID',
    `module`         varchar(64)  NOT NULL COMMENT '操作模块：URI 第二段（auth/users/knowledge/logs…）',
    `operation`      varchar(32)  NOT NULL COMMENT '操作类型：query/create/update/delete/login/logout/upload/download/other',
    `request_method` varchar(8)   NOT NULL COMMENT 'HTTP 方法',
    `request_uri`    varchar(255) NOT NULL COMMENT '请求路径（不含 query）',
    `query_string`   varchar(512) DEFAULT NULL COMMENT '查询参数原文；不含请求体（体里可能有密码等敏感字段）',
    `operator_id`    varchar(64)  DEFAULT NULL COMMENT '操作人用户ID；匿名/登录失败时为空',
    `operator_name`  varchar(64)  DEFAULT NULL COMMENT '操作人账号',
    `operator_ip`    varchar(64)  DEFAULT NULL COMMENT '客户端 IP（优先 X-Forwarded-For 首段）',
    `result`         tinyint      NOT NULL DEFAULT 1 COMMENT '结果：1 成功，0 失败（HTTP 状态码 >= 400）',
    `status_code`    int          NOT NULL DEFAULT 200 COMMENT 'HTTP 状态码',
    `cost_ms`        bigint       NOT NULL DEFAULT 0 COMMENT '处理耗时（毫秒）',
    `trace_id`       varchar(64)  DEFAULT NULL COMMENT '链路追踪 ID，与日志文件的 %X{traceId} 对应，便于跨库排查',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_module_operation` (`module`, `operation`),
    KEY `idx_operator` (`operator_id`),
    KEY `idx_result_time` (`result`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统操作日志';

-- ----------------------------------------------------------------------------
-- 菜单种子：系统管理 › 系统日志（挂在 m16 系统管理下，排在数据字典之后）
-- 注意：m20 已被本库的「数据字典」菜单占用，系统日志使用 m21。
-- permission = logs，与 URI /api/logs 的第二段对应——
-- 未授权用户访问 /api/logs 会被 MenuAccessInterceptor 拦为 403。
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m21', 'm16', '系统日志', 'system_log', 2, '/system/log', 'system/OperationLogView.vue',
     'Tickets', 5, 1, 'logs', '0', 'init', NOW(), NOW());

-- 超级管理员默认授权（普通用户不授，需要时在角色管理里手工分配）
INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES ('rm_sup_m21', 'r_super', 'm21', 'init', NOW());
