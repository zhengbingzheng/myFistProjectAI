-- 初始化脚本：默认使用数据库 erupt-example
-- 执行：mysql -uroot -p < src/main/resources/db/schema.sql
CREATE DATABASE IF NOT EXISTS `erupt-example`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `erupt-example`;

-- t_user：新建库按本脚本创建。
-- 实测已有库（erupt-example）中 id / username / username_active 三列是 varchar(64)，
-- 本脚本写的是 VARCHAR(32)。两者都能正常跑：应用层主键是 32 位 UUID 去横线，
-- 用户名在 UserCreateRequest 里由 @Size(max = 32) 卡住，32 装得下。
-- 差异来自该库是早期从别处迁移而来，无需为了对齐而 ALTER 已有库——
-- 只在新建库时按本脚本走即可，列更窄反而能更早暴露越界写入。
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

-- t_knowledge 已由 erupt-example 库提供，此处不重复建表，真实结构如下
-- （以下取自 information_schema.COLUMNS / SHOW INDEX 实测，供字段映射参考）：
--   id                varchar(64)   NOT NULL            主键（PRI）
--   knowledge_name    varchar(255)  NULL                标题
--   knowledge_content mediumtext    NULL                正文（UEditor）
--   knowledge_type    varchar(255)  NULL                类型
--   create_user_id    varchar(64)   NULL                创建人（t_user.id，字符串主键）
--   update_user_id    varchar(64)   NULL                更新人
--   create_time       datetime(6)   NULL                创建时间
--   update_time       datetime(6)   NULL                更新时间
-- 索引：PRIMARY(id)、FULLTEXT ft_knowledge_search(knowledge_name, knowledge_content)，无普通二级索引。
-- 注意：knowledge_name 上的 MUL 来自全文索引的内部结构，不是可用的等值索引；
--       按 knowledge_name 精确查会走全表扫描——这类查询请改用 knowledge_type + 分页条件。
-- 注：库表无唯一索引，「同类型下标题唯一」仅由业务层（KnowledgeServiceImpl）校验。
-- 注：id 列类型为 varchar(64)，与下方 t_navigation 一致；migrate_id_to_varchar32.sql 里写的
--     是 varchar(32)，它是历史迁移脚本，实际库以本条为准。

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

-- ============================================================
-- 数据字典：字典类型表 + 字典项表
--
-- 用途：集中维护系统里各类下拉/枚举选项（如用户状态、启用禁用），避免枚举散落在代码里。
-- 两层结构：先定义「字典类型」（如 用户状态），再在其下维护若干「字典项」（如 启用=1 / 禁用=0）。
--
-- 两表通过 dict_type 业务字段关联，不加外键——与 t_role_menu 用 id 业务关联的约定一致。
-- 删除为逻辑删除（del_flag='1'），不物理删除。
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_dict` (
    `id`                VARCHAR(32)  NOT NULL COMMENT '主键，字符串，32 位随机串（UUID 去横线），由应用层生成',
    `dict_type`         VARCHAR(64)  NOT NULL COMMENT '字典类型编码，如 sys_user_status；业务与其它模块按此编码取用',
    `dict_name`         VARCHAR(100) NOT NULL COMMENT '字典类型名称，如 用户状态',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 禁用',
    `remark`            VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `del_flag`          CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除（仅软删除，不物理删除）',
    `create_by`         VARCHAR(64)  DEFAULT NULL COMMENT '创建人（账号）',
    `update_by`         VARCHAR(64)  DEFAULT NULL COMMENT '更新人（账号）',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `dict_type_active`  VARCHAR(64)  GENERATED ALWAYS AS (IF(`del_flag` = '0', `dict_type`, NULL)) STORED COMMENT '生成列：仅未删除的类型参与唯一约束',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dict_type_active` (`dict_type_active`),
    KEY `idx_status_del_flag` (`status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='数据字典-类型表';

CREATE TABLE IF NOT EXISTS `t_dict_item` (
    `id`          VARCHAR(32)  NOT NULL COMMENT '主键，字符串，32 位随机串（UUID 去横线），由应用层生成',
    `dict_type`   VARCHAR(64)  NOT NULL COMMENT '所属字典类型编码，业务关联 t_dict.dict_type，不加外键',
    `label`       VARCHAR(100) NOT NULL COMMENT '显示文本，如 启用',
    `value`       VARCHAR(100) NOT NULL COMMENT '存储值，如 1',
    `sort_num`    INT          NOT NULL DEFAULT 0 COMMENT '排序号，升序',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 禁用',
    `list_class`  VARCHAR(32)  DEFAULT NULL COMMENT '前端标签样式：success/warning/danger/info/primary',
    `is_default`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认值：1 是，0 否',
    `remark`      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `del_flag`    CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除（仅软删除，不物理删除）',
    `create_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（账号）',
    `update_by`   VARCHAR(64)  DEFAULT NULL COMMENT '更新人（账号）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dict_type_sort_num` (`dict_type`, `sort_num`),
    KEY `idx_dict_type_del_flag` (`dict_type`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='数据字典-字典项表';

-- 知识列表「标题 + 正文」模糊查询使用的全文索引（方案 A：替代 LIKE '%kw%' 的全表扫描）
-- 依赖 MySQL 5.7.6+ 内置的 ngram 解析器；ngram_token_size 默认 2，即中文按 2 字切分，
-- 单字关键词无法命中，服务层会自动降级为 LIKE。
-- 若所在环境未创建该索引，请将 application.yml 的 app.knowledge.fulltext-enabled 置为 false；
-- 索引已存在时跳过本语句（重复创建会报 duplicate key name）。
ALTER TABLE `t_knowledge`
    ADD FULLTEXT INDEX `ft_knowledge_search` (`knowledge_name`, `knowledge_content`) WITH PARSER ngram;

-- t_navigation 已由 erupt-example 库提供，此处不重复建表，真实结构如下
-- （以下取自 information_schema.COLUMNS / SHOW INDEX 实测，供字段映射参考）：
--   id              varchar(64)   NOT NULL   主键（PRI）
--   navigation_name varchar(255)  NULL       名称
--   navigation_url  varchar(255)  NULL       跳转地址
--   navigation_img  varchar(255)  NULL       图标
--   cat             varchar(255)  NULL       分类
--   desc            varchar(255)  NULL       描述（MySQL 保留字，SQL 中需反引号转义）
--   tag             varchar(255)  NULL       标签
--   create_user_id  varchar(64)   NULL       创建人（t_user.id）
--   update_user_id  varchar(64)   NULL       更新人（t_user.id）
--   create_time     datetime(6)   NULL       创建时间
--   update_time     datetime(6)   NULL       更新时间
-- 索引：仅有 PRIMARY(id)，cat / tag 都没有索引。
-- 注：库表无唯一索引，按约定不做重名校验（导入时的「同分类同名」判重由业务层在内存里做）。
-- 注：id 列类型为 varchar(64)，migrate_id_to_varchar32.sql 里写的是 varchar(32)，
--     它是历史迁移脚本，实际库以本条为准。

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
-- 重要：生产环境首次登录后应立即修改该密码。
-- 注意：下面的 ON DUPLICATE KEY UPDATE 故意不含 password 列——
-- 重跑本脚本不会把管理员已改过的密码重置回默认值。
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
    nickname = VALUES(nickname),
    status = VALUES(status),
    update_by = 'system',
    update_time = NOW();
