-- ============================================================================
-- 智能体模块：GLM-5.3 / GLM-5.3-Flash 多轮对话
--
-- 执行方式（务必带字符集，否则中文入库成乱码）：
--   mysql --default-character-set=utf8mb4 -uroot -p erupt-example < schema-agent.sql
--
-- 幂等：建表 IF NOT EXISTS；菜单 / 授权 / 字典种子均为「不存在才插入」，
--       不会覆盖你在数据字典里改过的值。
--
-- 内容三部分：
--   1. 两张业务表：会话 + 消息（多轮对话的上下文就来自消息表）
--   2. 字典种子：类型 agent_config 及其配置项（API Key / 端点 / 默认模型 / 系统提示词）
--      —— 改字典即生效，无需重启后端
--   3. 菜单：m23 智能体（顶级，/agent），授权超级管理员与普通用户
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 会话表：一行 = 一次多轮对话
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_conversation` (
    `id`          VARCHAR(64)  NOT NULL COMMENT '主键，字符串，UUID 去横线，由应用层生成',
    `user_id`     VARCHAR(64)  NOT NULL COMMENT '归属用户，业务关联 t_user.id；会话只对创建者可见',
    `title`       VARCHAR(100) NOT NULL COMMENT '标题，取首条消息前 50 字',
    `model`       VARCHAR(40)  NOT NULL COMMENT '会话使用的模型，如 glm-5.3 / glm-5.3-flash',
    `del_flag`    CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常，1 已删除（仅软删除）',
    `create_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（用户 ID）',
    `update_by`   VARCHAR(64)  DEFAULT NULL COMMENT '更新人（用户 ID）',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（毫秒精度：同秒内的 user/assistant 消息也要能正确排序）',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（每轮对话刷新）',
    PRIMARY KEY (`id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '智能体-会话表';

-- ----------------------------------------------------------------------------
-- 2. 消息表：一行 = 一条 user / assistant 消息
--    没有_del_flag：会话删除即整段不可达，消息跟随会话生命周期
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_agent_message` (
    `id`                VARCHAR(64)  NOT NULL COMMENT '主键，字符串，UUID 去横线，由应用层生成',
    `conversation_id`   VARCHAR(64)  NOT NULL COMMENT '所属会话，业务关联 t_agent_conversation.id，不加外键',
    `role`              VARCHAR(16)  NOT NULL COMMENT '角色：user / assistant',
    `content`           MEDIUMTEXT   NOT NULL COMMENT '消息正文（Markdown）；附件正文不落这里，避免回显与上下文重复膨胀',
    `attachments`       TEXT         DEFAULT NULL COMMENT '附件 JSON 数组（[{id,name,contentType,size,parsed}]）；只存元信息，文件本体在 t_attachment',
    `reasoning`         TEXT         DEFAULT NULL COMMENT '思考过程（GLM-5.3 的 reasoning_content，仅 assistant）',
    `model`             VARCHAR(40)  DEFAULT NULL COMMENT '生成该消息的模型，仅 assistant 消息有值',
    `prompt_tokens`     INT          DEFAULT NULL COMMENT '提示词 tokens（上游 usage），仅 assistant 消息有值',
    `completion_tokens` INT          DEFAULT NULL COMMENT '补全 tokens（上游 usage），仅 assistant 消息有值',
    `cost_ms`           INT          DEFAULT NULL COMMENT '上游调用耗时（毫秒），仅 assistant 消息有值',
    `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（毫秒精度：单轮内 user/assistant 两条消息可能同秒落库）',
    PRIMARY KEY (`id`),
    KEY `idx_conv_time` (`conversation_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '智能体-消息表';

-- ----------------------------------------------------------------------------
-- 3. 字典种子：agent_config —— API Key / 端点 / 默认模型 / 系统提示词
--    说明：value 列 VARCHAR(100)，智谱 API Key（形如 id.secret）放得下；
--          API Key 项初始为空，填上即可用，后端每次调用实时读字典，改完即生效
-- ----------------------------------------------------------------------------
INSERT INTO `t_dict` (`id`, `dict_type`, `dict_name`, `status`, `remark`, `del_flag`, `create_by`, `create_time`, `update_time`)
SELECT 'dict_agent_config', 'agent_config', '智能体配置', 1,
       'GLM 智能体的上游配置：API Key / 端点 / 默认模型 / 系统提示词。后端每次调用实时读取，改完即生效',
       '0', 'init', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `t_dict` WHERE `dict_type` = 'agent_config' AND `del_flag` = '0');

INSERT INTO `t_dict_item` (`id`, `dict_type`, `label`, `value`, `sort_num`, `status`, `remark`, `del_flag`, `create_by`, `create_time`, `update_time`)
SELECT 'di_agent_api_key', 'agent_config', 'API Key', '', 1, 1,
       '智谱开放平台 API Key，形如 xxxxxxxx.xxxxxxxx；留空表示未配置，对话时会提示去哪里填', '0', 'init', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `t_dict_item` WHERE `dict_type` = 'agent_config' AND `label` = 'API Key' AND `del_flag` = '0');

INSERT INTO `t_dict_item` (`id`, `dict_type`, `label`, `value`, `sort_num`, `status`, `remark`, `del_flag`, `create_by`, `create_time`, `update_time`)
SELECT 'di_agent_chat_url', 'agent_config', 'chat_url', 'https://open.bigmodel.cn/api/paas/v4/chat/completions', 2, 1,
       '对话补全端点（OpenAI Chat 协议），一般无需修改', '0', 'init', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `t_dict_item` WHERE `dict_type` = 'agent_config' AND `label` = 'chat_url' AND `del_flag` = '0');

INSERT INTO `t_dict_item` (`id`, `dict_type`, `label`, `value`, `sort_num`, `status`, `remark`, `del_flag`, `create_by`, `create_time`, `update_time`)
SELECT 'di_agent_default_model', 'agent_config', 'default_model', 'glm-5.3', 3, 1,
       '新建会话的默认模型；前端可切换 glm-5.3 / glm-5.3-flash', '0', 'init', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `t_dict_item` WHERE `dict_type` = 'agent_config' AND `label` = 'default_model' AND `del_flag` = '0');

INSERT INTO `t_dict_item` (`id`, `dict_type`, `label`, `value`, `sort_num`, `status`, `remark`, `del_flag`, `create_by`, `create_time`, `update_time`)
SELECT 'di_agent_system_prompt', 'agent_config', 'system_prompt',
       '你是「ERUP 管理系统」内置的 AI 助手，用简体中文回答，风格简洁准确，代码用 Markdown 代码块。', 4, 1,
       '系统提示词，每轮对话自动附加在 messages 首位；清空则不发送 system 消息', '0', 'init', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `t_dict_item` WHERE `dict_type` = 'agent_config' AND `label` = 'system_prompt' AND `del_flag` = '0');

-- ----------------------------------------------------------------------------
-- 4. 菜单：m23 智能体（顶级页面），授权超管 + 普通用户
--    /api/agent/** 走 MenuAccessInterceptor 的通用规则：URI 第二段 = agent
--    → 校验 permission = agent，只有被授权角色的用户能调
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m23', '0', '智能体', 'agent', 2, '/agent', 'agent/AgentView.vue',
     'ChatLineRound', 80, 1, 'agent', '0', 'init', NOW(), NOW());

INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m23', 'r_super', 'm23', 'init', NOW()),
    ('rm_u_m23', 'r_user', 'm23', 'init', NOW());
