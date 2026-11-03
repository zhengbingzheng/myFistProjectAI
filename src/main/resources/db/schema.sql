-- 初始化脚本：默认使用数据库 erupt-example
-- 执行：mysql -uroot -p < src/main/resources/db/schema.sql
CREATE DATABASE IF NOT EXISTS `erupt-example`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `erupt-example`;

CREATE TABLE IF NOT EXISTS `t_user` (
    `id`              VARCHAR(32)  NOT NULL COMMENT '主键，字符串，32 位随机串（UUID 去横线），由应用层生成',
    `username`        VARCHAR(32)  NOT NULL COMMENT '登录账号（仅未删除记录唯一）',
    `password`        VARCHAR(100) NOT NULL COMMENT '登录密码哈希（BCrypt），不存明文',
    `nickname`        VARCHAR(32)  DEFAULT NULL COMMENT '昵称/显示名',
    `avatar`          VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
    `email`           VARCHAR(64)  DEFAULT NULL COMMENT '邮箱',
    `phone`           VARCHAR(16)  DEFAULT NULL COMMENT '手机号',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 禁用',
    `remark`          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `last_login_time` DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    `del_flag`        CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除（仅软删除，不物理删除）',
    `delete_by`       VARCHAR(64)  DEFAULT NULL COMMENT '删除操作人（账号），软删除时填充',
    `delete_time`     DATETIME     DEFAULT NULL COMMENT '删除时间',
    `create_by`       VARCHAR(64)  DEFAULT NULL COMMENT '创建人（账号），接入登录态后填充',
    `update_by`       VARCHAR(64)  DEFAULT NULL COMMENT '更新人（账号），接入登录态后填充',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `username_active` VARCHAR(32)  GENERATED ALWAYS AS (IF(`del_flag` = '0', `username`, NULL)) STORED COMMENT '生成列：仅未删除账号参与唯一约束',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username_active` (`username_active`),
    KEY `idx_status_create_time` (`status`, `create_time`),
    KEY `idx_phone` (`phone`),
    KEY `idx_email` (`email`),
    KEY `idx_del_flag` (`del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';

-- t_knowledge 已由 erupt-example 库提供，此处不重复建表，真实结构如下（供字段映射参考）：
--   id                bigint(20)    NOT NULL AUTO_INCREMENT  主键
--   knowledge_name    varchar(255)                           标题
--   knowledge_content mediumtext                             正文（UEditor）
--   knowledge_type    varchar(255)                           类型
--   create_user_id    bigint(20)                             创建人
--   update_user_id    bigint(20)                             更新人
--   create_time       datetime(6)                            创建时间
--   update_time       datetime(6)                            更新时间
-- 注：库表无唯一索引，「同类型下标题唯一」仅由业务层（KnowledgeServiceImpl）校验。

-- 文件附件表：仅描述文件自身属性，不含任何业务关联字段，也不含审核相关字段。
-- 主键使用字符串（32 位 UUID 去横线），不做自增整型；删除为两级：
--   一级 del_flag=1 进回收站；二级（回收站删除）才物理删除记录与文件。
CREATE TABLE IF NOT EXISTS `t_attachment` (
    `id`                  VARCHAR(32)  NOT NULL COMMENT '主键，字符串，32 位随机串（UUID 去横线）',
    `original_name`       VARCHAR(255) NOT NULL COMMENT '原始文件名（含扩展名），展示与下载时还原',
    `file_ext`            VARCHAR(16)  DEFAULT NULL COMMENT '扩展名，如 pdf；无扩展名时为空',
    `file_type`           VARCHAR(32)  NOT NULL DEFAULT 'other' COMMENT '分类：image/document/video/audio/archive/other',
    `content_type`        VARCHAR(128) DEFAULT NULL COMMENT '标准媒体类型，决定能否内联预览',
    `file_size`           BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
    `file_hash`           VARCHAR(64)  DEFAULT NULL COMMENT '内容 SHA-256 摘要，用于秒传去重与完整性校验',
    `storage_type`        VARCHAR(16)  NOT NULL DEFAULT 'local' COMMENT '存储方式：local/oss/db',
    `storage_path`        VARCHAR(512) NOT NULL COMMENT '相对存储根目录的路径，不对外暴露',
    `thumbnail_path`      VARCHAR(512) DEFAULT NULL COMMENT '缩略图相对路径（当前不生成，预留）',
    `download_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '下载次数',
    `remark`              VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `create_by`           VARCHAR(64)  DEFAULT NULL COMMENT '创建人（上传人），接入登录态后填充',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（上传时间）',
    `update_by`           VARCHAR(64)  DEFAULT NULL COMMENT '更新人',
    `update_time`         DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已进回收站',
    `delete_by`           VARCHAR(64)  DEFAULT NULL COMMENT '删除操作人（移入回收站）',
    `delete_time`         DATETIME     DEFAULT NULL COMMENT '移入回收站时间',
    `recycle_expire_time` DATETIME     DEFAULT NULL COMMENT '回收站保留到期时间，到期后自动彻底删除',
    PRIMARY KEY (`id`),
    KEY `idx_del_flag_create_time` (`del_flag`, `create_time`),
    KEY `idx_file_hash` (`file_hash`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件附件表';

-- 知识列表「标题 + 正文」模糊查询使用的全文索引（方案 A：替代 LIKE '%kw%' 的全表扫描）
-- 依赖 MySQL 5.7.6+ 内置的 ngram 解析器；ngram_token_size 默认 2，即中文按 2 字切分，
-- 单字关键词无法命中，服务层会自动降级为 LIKE。
-- 若所在环境未创建该索引，请将 application.yml 的 app.knowledge.fulltext-enabled 置为 false；
-- 索引已存在时跳过本语句（重复创建会报 duplicate key name）。
ALTER TABLE `t_knowledge`
    ADD FULLTEXT INDEX `ft_knowledge_search` (`knowledge_name`, `knowledge_content`) WITH PARSER ngram;

-- t_navigation 已由 erupt-example 库提供，此处不重复建表，真实结构如下（供字段映射参考）：
--   id              bigint(20)    NOT NULL AUTO_INCREMENT  主键
--   navigation_name varchar(255)                           名称
--   navigation_url  varchar(255)                           跳转地址
--   navigation_img  varchar(255)                           图标
--   cat             varchar(255)                           分类
--   desc            varchar(255)                           描述（MySQL 保留字，SQL 中需反引号转义）
--   tag             varchar(255)                           标签
--   create_user_id  bigint(20)                             创建人
--   update_user_id  bigint(20)                             更新人
--   create_time     datetime(6)                            创建时间
--   update_time     datetime(6)                            更新时间
-- 注：库表无唯一索引，按约定不做重名校验。

-- ============================================================
-- 初始数据：默认管理员账号
-- ============================================================
-- 账号：admin
-- 密码：Admin@123456
-- 密码哈希是 BCrypt（$2a$10$...，强度 10），明文密码为 Admin@123456
--
-- ⚠️ 必须用 utf8mb4 客户端执行本脚本，否则中文昵称会入库成乱码：
--    mysql --default-character-set=utf8mb4 -uroot -p < schema.sql
--    若已用默认字符集执行过，可用 ALTER 修复：
--    ALTER TABLE t_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--
-- 重要：生产环境首次登录后应立即修改该密码，并通过修改该 SQL 重置
-- ============================================================

SET NAMES utf8mb4;

INSERT INTO t_user (id, username, password, nickname, status, del_flag,
                    create_by, create_time, update_by, update_time)
VALUES ('0123456789abcdef0123456789abcdef',
        'admin',
        '$2a$10$VZvg/CJ/zLwdeNNia60lremDcV3m9XV6lojx7ZS1P6Jhe5jQkpYv2',
        '系统管理员',
        1, '0',
        'system', NOW(), 'system', NOW())
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    nickname = VALUES(nickname),
    status = VALUES(status),
    update_by = 'system',
    update_time = NOW();
