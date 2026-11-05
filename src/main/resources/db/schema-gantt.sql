-- ============================================================================
-- 时间规划（甘特图）模块建表与菜单种子
--
-- 执行方式（与其它 schema-*.sql 一致，手动执行一次即可）：
--   mysql --default-character-set=utf8mb4 -uroot -p erupt-example < src/main/resources/db/schema-gantt.sql
--
-- 内容：
--   1. t_gantt_plan        计划（一次规划 = 一张甘特图）
--   2. t_gantt_task        任务 / 里程碑（时间节点）
--   3. t_gantt_dependency  任务依赖（前后置关系，支撑并行/串行可视化）
--   4. 菜单 m24 时间规划（顶级，/gantt），授权超管与普通用户
--
-- 设计说明：
--   · 里程碑不单独建表，用 `is_milestone = 1` + 起止同一天表达。
--     理由：里程碑本质是零工期任务，允许被依赖、被排序、被统计；
--     单独建表会让「依赖关系」出现两套外键、甘特图也要渲染两种实体，
--     收益不足以抵消复杂度。
--   · 依赖关系单独建表而不是在任务表里存 parent/predecessor 字段：
--     一个任务可以有多个前置（多对多），塞进一行只能存 CSV，无法加唯一约束，
--     也没法在删任务时按外键精确清理。
--   · 所有删除都是软删除（del_flag），与知识库/会话保持一致。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 计划：一次规划对应一张甘特图
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_gantt_plan` (
    `id`          VARCHAR(64)  NOT NULL COMMENT '主键，应用层生成的 UUID 去横线',
    `plan_name`   VARCHAR(100) NOT NULL COMMENT '计划名称，如「2026 Q4 版本排期」',
    `plan_desc`   VARCHAR(500) DEFAULT NULL COMMENT '计划说明',
    `start_date`  DATE         NOT NULL COMMENT '计划起始日（仅用于初始视图范围，任务可超出）',
    `end_date`    DATE         NOT NULL COMMENT '计划结束日',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active 进行中 / archived 已归档',
    `del_flag`    CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（t_user.id）',
    `update_by`   VARCHAR(64)  DEFAULT NULL COMMENT '最后修改人（t_user.id）',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_del_start` (`del_flag`, `start_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='时间规划-计划';

-- ----------------------------------------------------------------------------
-- 2. 任务：甘特图上的一根条子；is_milestone=1 时渲染为菱形节点
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_gantt_task` (
    `id`           VARCHAR(64)  NOT NULL COMMENT '主键',
    `plan_id`      VARCHAR(64)  NOT NULL COMMENT '所属计划（业务关联 t_gantt_plan.id，不加外键）',
    `parent_id`    VARCHAR(64)  DEFAULT NULL COMMENT '父任务；NULL = 顶层。仅允许一层父子，用于分组汇总',
    `task_name`    VARCHAR(120) NOT NULL COMMENT '任务名称',
    `task_desc`    VARCHAR(500) DEFAULT NULL COMMENT '任务说明',
    `start_date`   DATE         NOT NULL COMMENT '开始日',
    `end_date`     DATE         NOT NULL COMMENT '结束日；里程碑时与 start_date 相同',
    `progress`     TINYINT      NOT NULL DEFAULT 0 COMMENT '完成度 0-100（父任务由子任务自动汇总）',
    `status`       VARCHAR(20)  NOT NULL DEFAULT 'todo' COMMENT '状态：todo 待开始 / doing 进行中 / done 已完成 / blocked 受阻',
    `assignee`     VARCHAR(40)  DEFAULT NULL COMMENT '负责人姓名（自由文本，不强制关联账号）',
    `priority`     TINYINT      NOT NULL DEFAULT 2 COMMENT '优先级：1 高 / 2 中 / 3 低',
    `is_milestone` TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 里程碑（时间节点）',
    `color`        VARCHAR(20)  DEFAULT NULL COMMENT '自定义条形色值，空则按状态取默认色',
    `sort_num`     INT          NOT NULL DEFAULT 0 COMMENT '同层排序号，越小越靠前',
    `del_flag`     CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记',
    `create_by`    VARCHAR(64)  DEFAULT NULL,
    `update_by`    VARCHAR(64)  DEFAULT NULL,
    `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_plan_sort` (`plan_id`, `del_flag`, `sort_num`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='时间规划-任务';

-- ----------------------------------------------------------------------------
-- 3. 依赖：from_task 必须先于 / 同步于 to_task
--    dep_type 语义（与 MS Project 一致）：
--      FS 完成-开始：前置完成后后置才能开始（最常用，默认）
--      SS 开始-开始：前置开始后后置才能开始（可并行推进）
--      FF 完成-完成：前置完成后后置才能完成
--      SF 开始-完成：前置开始后后置才能完成（极少用，保留兼容）
--    lag_days：滞后天数，正数表示额外等待，负数表示可提前（重叠）
-- ----------------------------------------------------------------------------
-- 依赖也是软删。唯一性不能直接建在 (from_task_id, to_task_id) 上：
-- 软删后这一对任务重新建依赖会撞键，等于删一次就再也建不回来。
-- 因此用 STORED 生成列 `alive_from_to`——有效记录取 `from:to`，已删除为 NULL，
-- MySQL 唯一索引不约束 NULL，于是墓碑可以有多条、有效记录仍唯一。
CREATE TABLE IF NOT EXISTS `t_gantt_dependency` (
    `id`           VARCHAR(64) NOT NULL COMMENT '主键',
    `plan_id`      VARCHAR(64) NOT NULL COMMENT '所属计划，冗余存储便于按计划一次性取出',
    `from_task_id` VARCHAR(64) NOT NULL COMMENT '前置任务 id',
    `to_task_id`   VARCHAR(64) NOT NULL COMMENT '后置任务 id',
    `dep_type`     VARCHAR(4)  NOT NULL DEFAULT 'FS' COMMENT '依赖类型：FS/SS/FF/SF',
    `lag_days`     INT         NOT NULL DEFAULT 0 COMMENT '滞后天数，可为负',
    `del_flag`     CHAR(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 1 已删除',
    `create_by`    VARCHAR(64) DEFAULT NULL,
    `update_by`    VARCHAR(64) DEFAULT NULL,
    `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `alive_from_to` VARCHAR(130)
        GENERATED ALWAYS AS (IF(`del_flag` = '0', CONCAT(`from_task_id`, ':', `to_task_id`), NULL)) STORED
        COMMENT '有效依赖的唯一标识：删除后置 NULL，不参与唯一约束',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alive_from_to` (`alive_from_to`) COMMENT '同一对任务只允许一条有效依赖',
    KEY `idx_plan` (`plan_id`),
    KEY `idx_from_to` (`from_task_id`, `to_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='时间规划-任务依赖';

-- ----------------------------------------------------------------------------
-- 4. 菜单：m24 时间规划（顶级页面），授权超管 + 普通用户
--    /api/gantt/** 走 MenuAccessInterceptor 的通用规则：URI 第二段 = gantt
--    → 校验 permission = gantt，只有被授权角色的用户能调
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m24', '0', '时间规划', 'gantt', 2, '/gantt', 'gantt/GanttView.vue',
     'Calendar', 85, 1, 'gantt', '0', 'init', NOW(), NOW());

INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m24', 'r_super', 'm24', 'init', NOW()),
    ('rm_u_m24', 'r_user', 'm24', 'init', NOW());
