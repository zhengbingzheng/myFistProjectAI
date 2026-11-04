-- =============================================================================
-- 知识模块：为列表排序补索引
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p < src/main/resources/db/migrate_knowledge_index.sql
-- =============================================================================
--
-- 背景
--   列表查询原先写的是 ORDER BY id DESC。主键改成应用层生成的 UUID 之后，
--   id 不再自带先后顺序，这个排序实际上等于「随机排列」——新录入的知识会散落在任意位置，
--   用户翻列表时找不到刚加的那条。
--
--   改为 ORDER BY create_time DESC, id DESC 之后语义正确了，但 t_knowledge 上
--   只有 PRIMARY(id) 与全文索引 ft_knowledge_search，没有 create_time 索引，
--   实测执行计划为 type=ALL + Using filesort：每查一次列表就整体排一遍。
--   13 行数据无所谓，数据量上去后这项开销会随行数线性增长。
--
-- 做法
--   建 (create_time, id) 联合索引。第二列 id 不是多余的：同一次批量导入的多行
--   create_time 完全相同（应用层用同一个 LocalDateTime.now() 赋值），
--   只按 create_time 排序时这些行之间的先后是不确定的，翻页会出现记录重复或遗漏。
--   把 id 加进索引后，ORDER BY create_time DESC, id DESC 可以完全走索引，既稳定又免排序。
--
-- 影响
--   该索引只服务于 ORDER BY 与按时间范围过滤，不影响全文检索路径
--   （MATCH ... AGAINST 仍走 ft_knowledge_search）。
-- =============================================================================

USE `erupt-example`;

-- 索引已存在时 MySQL 直接报 1061 duplicate key name，重跑本脚本会失败但不会破坏数据。
-- 若要可重入，可先执行：
--   ALTER TABLE `t_knowledge` DROP INDEX `idx_create_time`;
ALTER TABLE `t_knowledge`
    ADD INDEX `idx_create_time` (`create_time`, `id`);

-- 验证：应显示 key = idx_create_time，Extra 中不再出现 Using filesort
-- EXPLAIN SELECT id, knowledge_name, knowledge_type, create_user_id, update_user_id,
--                create_time, update_time
--         FROM `t_knowledge`
--         ORDER BY create_time DESC, id DESC
--         LIMIT 10 OFFSET 0;
