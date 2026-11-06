-- ============================================================================
-- 第 2 步：智能体用量统计（m26）+ AI 读库只读版（m27）
--
-- 不建新表：用量数据直接聚合 t_agent_message（role='assistant' 行携带
-- model / prompt_tokens / completion_tokens / cost_ms），
-- 消息没有归属列，统计时 JOIN t_agent_conversation 按 create_by 过滤——
-- 与「会话只对创建者可见」的归属规则一致。
--
-- 权限设计：两个功能的 API 都挂在 /api/agent/* 下（URI 第二段 = agent），
-- 拦截器按「智能体」菜单校验——能进智能体就能查自己的用量；
-- 页面菜单行用独立 permission（agent-usage / ai-data），超管可在角色管理里
-- 单独关闭统计页与读库页，而不影响智能体本身。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 菜单（编号顺延：m26 / m27）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m26', '0', '用量统计', 'agent_usage', 2, '/agent-usage', 'agent/AgentUsageView.vue',
     'DataAnalysis', 82, 1, 'agent-usage', '0', 'init', NOW(), NOW()),
    ('m27', '0', 'AI 读库', 'agent_data', 2, '/ai-data', 'agent/AIDataView.vue',
     'Coin', 84, 1, 'ai-data', '0', 'init', NOW(), NOW());

INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m26', 'r_super', 'm26', 'init', NOW()),
    ('rm_u_m26', 'r_user', 'm26', 'init', NOW()),
    ('rm_sup_m27', 'r_super', 'm27', 'init', NOW()),
    ('rm_u_m27', 'r_user', 'm27', 'init', NOW());
