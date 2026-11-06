-- ============================================================================
-- MD 文档查看（工具箱子菜单，m28）
--
-- 纯前端页面：无后端接口、不建任何表——与工具箱（m22）同一设计底线，
-- 打开的 md 文件在浏览器本地用 FileReader 读取、marked 渲染，
-- 文件内容不出本机。菜单挂在工具箱（m22）下，permission 沿用 tools：
-- 能用工具箱就能用，不必单独控权。
--
-- 父菜单 m22 原本是叶子单页菜单；加了这个子菜单后，侧边栏会自动
-- 把「工具箱」渲染成可展开的父节点（NavMenuItem 按 children 递归），无需改前端。
--
-- ⚠️ 副作用（已修复）：m22 变成父节点后父节点本身不可点，原工具箱页面
-- （JSON / YAML / Base64 …，路由 /tools）失去菜单入口——所以本文件同时补了
-- m29「数据转换」指回原页面，工具箱恢复「数据转换 + MD 文档查看」两级结构。
-- 教训：给单页菜单挂子菜单前，先确认原页面自己的入口不会被父节点吞掉。
-- ============================================================================

INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m28', 'm22', 'MD 文档查看', 'md-viewer', 2, '/tools/md', 'tools/MdViewerView.vue',
     'Document', 2, 1, 'tools', '0', 'init', NOW(), NOW()),
    ('m29', 'm22', '数据转换', 'tools-data', 2, '/tools/data', 'tools/ToolsView.vue',
     'SetUp', 1, 1, 'tools', '0', 'init', NOW(), NOW());

-- m28 入库后 m22 变成了父节点：侧边栏把「工具箱」渲染成可展开目录（父节点本身不可点），
-- 原工具箱页面（JSON / YAML / Base64 …）随之失去菜单入口。补 m29 指回原页面，
-- 工具箱恢复标准两级结构；/tools 路由保留（直接访问仍可用），但菜单入口走 /tools/data。
UPDATE `t_menu` SET `sort_num` = 2 WHERE `id` = 'm28' AND `sort_num` <> 2;

INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m28', 'r_super', 'm28', 'init', NOW()),
    ('rm_u_m28', 'r_user', 'm28', 'init', NOW()),
    ('rm_sup_m29', 'r_super', 'm29', 'init', NOW()),
    ('rm_u_m29', 'r_user', 'm29', 'init', NOW());
