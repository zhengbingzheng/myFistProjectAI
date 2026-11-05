-- ============================================================================
-- 工具箱菜单（纯前端页面：无后端接口、不新建任何表）
--
-- 执行方式（务必带字符集，否则中文注释入库成乱码）：
--   mysql --default-character-set=utf8mb4 -uroot -p erupt-example < schema-tools-menu.sql
--
-- 幂等：全部 INSERT IGNORE，可重复执行，不会覆盖已手工调整过的数据。
--
-- 设计说明：
--   1. 工具箱页面（views/tools/ToolsView.vue）的全部转换都在浏览器本地完成，
--      不调任何 /api 接口。permission='tools' 因此不参与接口鉴权
--      （MenuAccessInterceptor 只拦 /api/**），它只影响侧边栏可见性与前端路由守卫。
--   2. 菜单 ID 用 m22：m01~m20 属于 init-im-menu.sql，m21 是系统日志，此处顺延。
--   3. 默认同时授权超级管理员与普通用户——通用工具、无敏感数据；
--      不想给普通用户时在「角色管理」里去掉授权即可，无需改脚本。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 菜单：顶级页面（menu_type=2），挂在根下，排在即时通信（60）之后
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_menu`
    (`id`, `parent_id`, `menu_name`, `menu_code`, `menu_type`, `path`, `component`,
     `icon`, `sort_num`, `visible`, `permission`, `del_flag`, `create_by`, `create_time`, `update_time`)
VALUES
    ('m22', '0', '工具箱', 'tools', 2, '/tools', 'tools/ToolsView.vue',
     'MagicStick', 70, 1, 'tools', '0', 'init', NOW(), NOW());

-- ----------------------------------------------------------------------------
-- 授权：超级管理员 + 普通用户
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `t_role_menu` (`id`, `role_id`, `menu_id`, `create_by`, `create_time`)
VALUES
    ('rm_sup_m22', 'r_super', 'm22', 'init', NOW()),
    ('rm_u_m22', 'r_user', 'm22', 'init', NOW());
