-- ============================================================================
-- 迁移：t_gantt_dependency 改为软删除
--
-- 背景：计划 / 任务一直是软删（del_flag），依赖却是 DELETE 硬删，
--       删掉一个任务后，它参与过的依赖关系就再也捞不回来了。
--       与「所有删除都是软删除」的约定不一致。
--
-- 难点在唯一键：原来是 `uk_from_to (from_task_id, to_task_id)`，
-- 软删后这一对任务再建依赖会撞唯一键，删一次就再也建不回来。
-- 解决：加一个 STORED 生成列 `alive_from_to`——有效记录的值为
--       `from:to`，已删除记录为 NULL；唯一索引建在它上面。
--       MySQL 的唯一索引不约束 NULL，所以墓碑可以有多条，有效记录仍唯一。
--
-- 只执行一次。已有的 schema-gantt.sql 新建库已包含这些列，
-- 本脚本仅用于升级旧库。
-- ============================================================================

SET NAMES utf8mb4;

-- 1. 软删标记与审计列
ALTER TABLE `t_gantt_dependency`
    ADD COLUMN `del_flag`    CHAR(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 1 已删除',
    ADD COLUMN `update_by`   VARCHAR(64) DEFAULT NULL COMMENT '最后修改人',
    ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后修改时间';

-- 2. 唯一键改为「只约束有效记录」
ALTER TABLE `t_gantt_dependency`
    ADD COLUMN `alive_from_to` VARCHAR(130)
        GENERATED ALWAYS AS (IF(`del_flag` = '0', CONCAT(`from_task_id`, ':', `to_task_id`), NULL)) STORED
        COMMENT '有效依赖的唯一标识：删除后置 NULL，不参与唯一约束',
    DROP INDEX `uk_from_to`,
    ADD UNIQUE KEY `uk_alive_from_to` (`alive_from_to`),
    ADD KEY `idx_from_to` (`from_task_id`, `to_task_id`);

-- 3. 校验
SELECT COUNT(*) AS 依赖总数, SUM(del_flag = '0') AS 有效条数 FROM `t_gantt_dependency`;
SHOW INDEX FROM `t_gantt_dependency`;
