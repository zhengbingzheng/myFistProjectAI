-- ============================================================================
-- 即时通信 + 菜单授权 模块表结构
--
-- 统一约定（与既有 t_user 保持一致）：
--   · 主键 varchar(32)，应用层生成 UUID 去横线，不用自增
--   · del_flag char(1) 软删除，0 正常 / 1 已删除
--   · create_by / update_by / create_time / update_time 审计字段
--
-- 执行务必带字符集，否则中文注释与初始数据会入库成乱码：
--   mysql --default-character-set=utf8mb4 -uroot -p erupt-example < schema-im-menu.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 聊天会话
--    单聊会话由双方 user_id 排序后拼出 biz_key，保证同一对用户只有一个会话。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_chat_conversation` (
    `id`              varchar(32)  NOT NULL COMMENT '主键，32 位 UUID 去横线',
    `conv_type`       tinyint      NOT NULL DEFAULT 1 COMMENT '会话类型：1 单聊，2 群聊',
    `biz_key`         varchar(96)  DEFAULT NULL COMMENT '业务唯一键：单聊为双方用户ID升序拼接（32+1+32），群聊为 null',
    `conv_name`       varchar(128) DEFAULT NULL COMMENT '会话名称，单聊可为空（前端按成员展示）',
    `conv_avatar`     varchar(512) DEFAULT NULL COMMENT '会话头像',
    `owner_id`        varchar(32)  DEFAULT NULL COMMENT '创建人（群主）',
    `last_msg_id`     varchar(32)  DEFAULT NULL COMMENT '最后一条消息ID，用于列表摘要',
    `last_msg_time`   datetime     DEFAULT NULL COMMENT '最后一条消息时间，用于列表排序',
    `del_flag`        char(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_by`       varchar(64)  DEFAULT NULL COMMENT '创建人账号',
    `update_by`       varchar(64)  DEFAULT NULL COMMENT '更新人账号',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_owner_del` (`owner_id`, `del_flag`),
    KEY `idx_last_msg_time` (`last_msg_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '聊天会话';

-- ----------------------------------------------------------------------------
-- 2. 会话成员
--    未读计数冗余在此表，避免每次都去消息表 count。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_chat_member` (
    `id`                varchar(32) NOT NULL COMMENT '主键',
    `conversation_id`   varchar(32) NOT NULL COMMENT '会话ID',
    `user_id`           varchar(32) NOT NULL COMMENT '成员用户ID',
    `member_role`       tinyint     NOT NULL DEFAULT 1 COMMENT '成员角色：1 普通成员，2 群主',
    `last_read_msg_id`  varchar(32) DEFAULT NULL COMMENT '最后已读消息ID',
    `unread_count`      int         NOT NULL DEFAULT 0 COMMENT '未读消息数',
    `join_time`         datetime    DEFAULT NULL COMMENT '加入时间',
    `create_by`         varchar(64) DEFAULT NULL COMMENT '创建人账号',
    `del_flag`          char(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已退出',
    `create_time`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_user` (`conversation_id`, `user_id`),
    KEY `idx_user_unread` (`user_id`, `unread_count`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '会话成员';

-- ----------------------------------------------------------------------------
-- 3. 聊天消息（记录持久化）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_chat_message` (
    `id`              varchar(32) NOT NULL COMMENT '主键',
    `conversation_id` varchar(32) NOT NULL COMMENT '会话ID',
    `sender_id`       varchar(32) NOT NULL COMMENT '发送人ID',
    `receiver_id`     varchar(32) DEFAULT NULL COMMENT '接收人ID（单聊冗余，便于直接定位接收方）',
    `msg_type`        tinyint     NOT NULL DEFAULT 1 COMMENT '消息类型：1 文本，2 图片，3 文件，4 系统通知',
    `content`         text        COMMENT '消息内容',
    `attachment_id`   varchar(32) DEFAULT NULL COMMENT '附件ID（图片/文件消息）',
    `read_flag`       tinyint     NOT NULL DEFAULT 0 COMMENT '已读标记：0 未读，1 已读',
    `read_time`       datetime    DEFAULT NULL COMMENT '读取时间',
    `del_flag`        char(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_by`       varchar(64) DEFAULT NULL COMMENT '创建人账号',
    `create_time`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    `update_time`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_conv_time` (`conversation_id`, `create_time`),
    KEY `idx_receiver_read` (`receiver_id`, `read_flag`),
    KEY `idx_sender` (`sender_id`),
    KEY `idx_attachment` (`attachment_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '聊天消息';

-- ----------------------------------------------------------------------------
-- 4. 菜单（多级，支持无限层级树）
--    parent_id 为 '0' 表示顶级；靠 sort_num 同级排序，靠 parent_id 递归成树。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_menu` (
    `id`          varchar(32)  NOT NULL COMMENT '主键',
    `parent_id`   varchar(32)  NOT NULL DEFAULT '0' COMMENT '父菜单ID，顶级为 0',
    `menu_name`   varchar(64)  NOT NULL COMMENT '菜单名称',
    `menu_code`   varchar(64)  DEFAULT NULL COMMENT '菜单编码，前端/接口层做稳定标识',
    `menu_type`   tinyint      NOT NULL DEFAULT 2 COMMENT '类型：1 目录，2 菜单，3 按钮',
    `path`        varchar(255) DEFAULT NULL COMMENT '路由地址，仅菜单类型有值',
    `component`   varchar(255) DEFAULT NULL COMMENT '前端组件路径',
    `icon`        varchar(64)  DEFAULT NULL COMMENT '图标',
    `sort_num`    int          NOT NULL DEFAULT 0 COMMENT '同级排序号，小者在前',
    `visible`     tinyint      NOT NULL DEFAULT 1 COMMENT '是否显示：0 隐藏，1 显示',
    `permission`  varchar(128) DEFAULT NULL COMMENT '权限标识，预留给按钮级鉴权',
    `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
    `del_flag`    char(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人账号',
    `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人账号',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_menu_code` (`menu_code`),
    KEY `idx_parent_sort` (`parent_id`, `sort_num`),
    KEY `idx_del_flag` (`del_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统菜单';

-- ----------------------------------------------------------------------------
-- 5. 角色（用户分级）
--    role_level 越小权限越高：0 超级管理员，10 普通用户。
--    分级用于两件事：菜单授权的数据范围、以及防止越权给自个儿提权。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_role` (
    `id`          varchar(32) NOT NULL COMMENT '主键',
    `role_code`   varchar(64) NOT NULL COMMENT '角色编码，程序内引用',
    `role_name`   varchar(64) NOT NULL COMMENT '角色名称',
    `role_level`  int         NOT NULL DEFAULT 10 COMMENT '角色等级，数字越小权限越高（0 为超级管理员）',
    `description` varchar(500) DEFAULT NULL COMMENT '角色描述',
    `del_flag`    char(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_by`   varchar(64) DEFAULT NULL COMMENT '创建人账号',
    `update_by`   varchar(64) DEFAULT NULL COMMENT '更新人账号',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`),
    KEY `idx_level` (`role_level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统角色';

-- ----------------------------------------------------------------------------
-- 6. 用户-角色关联（多对多）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user_role` (
    `id`          varchar(32) NOT NULL COMMENT '主键',
    `user_id`     varchar(32) NOT NULL COMMENT '用户ID',
    `role_id`     varchar(32) NOT NULL COMMENT '角色ID',
    `del_flag`    char(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已解除',
    `create_by`   varchar(64) DEFAULT NULL COMMENT '创建人账号',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户角色关联';

-- ----------------------------------------------------------------------------
-- 7. 角色-菜单关联（多对多，授权的落点）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_role_menu` (
    `id`          varchar(32) NOT NULL COMMENT '主键',
    `role_id`     varchar(32) NOT NULL COMMENT '角色ID',
    `menu_id`     varchar(32) NOT NULL COMMENT '菜单ID',
    `create_by`   varchar(64) DEFAULT NULL COMMENT '创建人账号',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
    KEY `idx_menu` (`menu_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色菜单关联';

-- 聊天附件：与文件管理 t_attachment 相互独立。
-- 分开的理由：共用一张表时，聊天里发的图会出现在文件列表里，
-- 而文件列表清空回收站又会顺手带走聊天记录里的图。
CREATE TABLE IF NOT EXISTS `t_chat_attachment`
(
    `id`            varchar(32)  NOT NULL COMMENT '主键',
    `original_name` varchar(255) NOT NULL COMMENT '原始文件名',
    `file_ext`      varchar(32) DEFAULT NULL COMMENT '扩展名（小写，不含点）',
    `content_type`  varchar(128) DEFAULT NULL COMMENT 'MIME 类型',
    `file_size`     bigint       NOT NULL DEFAULT 0 COMMENT '字节数',
    `storage_path`  varchar(512) NOT NULL COMMENT '相对存储根目录的路径',
    `uploader_id`   varchar(32)  NOT NULL COMMENT '上传者用户ID',
    `del_flag`      char(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除',
    `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    KEY `idx_uploader` (`uploader_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '聊天附件（与文件管理 t_attachment 相互独立）';
