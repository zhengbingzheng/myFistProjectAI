-- =============================================================================
-- 迁移：把 t_ 开头各表中「非 varchar」的主键 id 改成 varchar(32)
-- 涉及表：t_area、t_customer_archive、t_file_upload_log、t_knowledge、t_navigation、t_sample
-- =============================================================================
-- 背景
--   本项目各业务表的主键统一为「应用层生成的 32 位字符串」（UUID 去横线），不用数据库自增。
--   此前仍有部分表沿用 bigint AUTO_INCREMENT，带来两个问题：
--     1. DAO 层方法签名被迫分裂成 Long / String 两套，按 id 查询、批量删除都要各写一遍；
--     2. 自增 id 会把业务规模暴露在外，将来数据合并或迁移时主键容易冲突。
--
-- 历史数据转换规则
--   原有 bigint 值统一左补 0 到 32 位（如 1 → '00000000000000000000000000000001'）。
--   选 LPAD 而不是重新生成 UUID，是因为：
--     确定性：同一份数据多次执行结果一致，可重复、可校验；
--     保引用：外键子列（t_area.pid、t_customer_archive 的 area/city/province/country）
--             做同样的 LPAD 之后仍指向同一条父记录，不必维护新旧 id 映射表；
--     保顺序：补零后字典序与原数值序一致，排查问题时容易和迁移前的数据对上。
--   新数据由应用层生成 32 位字符串，与历史数据宽度一致，不会出现长短混杂。
--
-- 执行前务必先备份数据库。本脚本只改列类型与历史值，不删数据。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) 先删外键：修改被引用列的类型时，不能有任何外键指向它
-- ---------------------------------------------------------------------------
ALTER TABLE `t_customer_archive` DROP FOREIGN KEY `FK23e1ox5eyuk9cuqqog6xlh63l`;
ALTER TABLE `t_customer_archive` DROP FOREIGN KEY `FKi5124qnuh34eogyjjdltcc3dl`;
ALTER TABLE `t_customer_archive` DROP FOREIGN KEY `FKq1c95339jffaj8pgw1regxfib`;
ALTER TABLE `t_customer_archive` DROP FOREIGN KEY `FKti30v19j7s4i28fiyig9fanqj`;
ALTER TABLE `t_area` DROP FOREIGN KEY `FKergdmtf7a8x8quqqt46cwkh0q`;

-- ---------------------------------------------------------------------------
-- 2) t_area（含自引用列 pid）
-- ---------------------------------------------------------------------------
-- MODIFY 不带 AUTO_INCREMENT，顺带去掉自增属性；必须先改成 varchar 再 UPDATE，
-- 否则 LPAD 出来的字符串写回 bigint 列会被转回数字（前导零丢失）。
ALTER TABLE `t_area` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';
UPDATE `t_area` SET `id` = LPAD(`id`, 32, '0');
ALTER TABLE `t_area` MODIFY `pid` varchar(32) DEFAULT NULL COMMENT '上级地区主键';
UPDATE `t_area` SET `pid` = LPAD(`pid`, 32, '0') WHERE `pid` IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3) t_customer_archive（country/province/city/area 四列引用 t_area.id）
-- ---------------------------------------------------------------------------
ALTER TABLE `t_customer_archive` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';
UPDATE `t_customer_archive` SET `id` = LPAD(`id`, 32, '0');

ALTER TABLE `t_customer_archive` MODIFY `country` varchar(32) DEFAULT NULL COMMENT '国家';
UPDATE `t_customer_archive` SET `country` = LPAD(`country`, 32, '0') WHERE `country` IS NOT NULL;
ALTER TABLE `t_customer_archive` MODIFY `province` varchar(32) DEFAULT NULL COMMENT '省份';
UPDATE `t_customer_archive` SET `province` = LPAD(`province`, 32, '0') WHERE `province` IS NOT NULL;
ALTER TABLE `t_customer_archive` MODIFY `city` varchar(32) DEFAULT NULL COMMENT '城市';
UPDATE `t_customer_archive` SET `city` = LPAD(`city`, 32, '0') WHERE `city` IS NOT NULL;
ALTER TABLE `t_customer_archive` MODIFY `area` varchar(32) DEFAULT NULL COMMENT '区县';
UPDATE `t_customer_archive` SET `area` = LPAD(`area`, 32, '0') WHERE `area` IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4) t_knowledge
-- ---------------------------------------------------------------------------
ALTER TABLE `t_knowledge` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';
UPDATE `t_knowledge` SET `id` = LPAD(`id`, 32, '0');

-- ---------------------------------------------------------------------------
-- 5) t_navigation
-- ---------------------------------------------------------------------------
ALTER TABLE `t_navigation` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';
UPDATE `t_navigation` SET `id` = LPAD(`id`, 32, '0');

-- ---------------------------------------------------------------------------
-- 6) t_file_upload_log / t_sample（当前无数据，只改列类型）
-- ---------------------------------------------------------------------------
ALTER TABLE `t_file_upload_log` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';
ALTER TABLE `t_sample` MODIFY `id` varchar(32) NOT NULL COMMENT '主键，32 位字符串';

-- ---------------------------------------------------------------------------
-- 7) 恢复外键：两端都已是 varchar(32)，引用关系与迁移前完全一致
-- ---------------------------------------------------------------------------
ALTER TABLE `t_area`
    ADD CONSTRAINT `FKergdmtf7a8x8quqqt46cwkh0q` FOREIGN KEY (`pid`) REFERENCES `t_area` (`id`);
ALTER TABLE `t_customer_archive`
    ADD CONSTRAINT `FKq1c95339jffaj8pgw1regxfib` FOREIGN KEY (`country`) REFERENCES `t_area` (`id`);
ALTER TABLE `t_customer_archive`
    ADD CONSTRAINT `FKi5124qnuh34eogyjjdltcc3dl` FOREIGN KEY (`province`) REFERENCES `t_area` (`id`);
ALTER TABLE `t_customer_archive`
    ADD CONSTRAINT `FKti30v19j7s4i28fiyig9fanqj` FOREIGN KEY (`city`) REFERENCES `t_area` (`id`);
ALTER TABLE `t_customer_archive`
    ADD CONSTRAINT `FK23e1ox5eyuk9cuqqog6xlh63l` FOREIGN KEY (`area`) REFERENCES `t_area` (`id`);
