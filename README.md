# erupt-example

基于 **Spring Boot 4.1.1**（当前最新 GA）+ **MyBatis 4.1.0** + **MySQL 8+** 的标准分层后端工程。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 4.1.1 | 最新 GA 版本（Spring Framework 7.x） |
| JDK | 26 | 本地 `JDK 26.0.2`，Spring Boot 4 基线 JDK 21+ |
| MyBatis | mybatis-spring-boot-starter 4.1.0 | ORM + `@Mapper` 扫描 |
| MySQL | Connector/J 26.7.0 | `com.mysql:mysql-connector-j` |
| 连接池 | HikariCP | Spring Boot 默认连接池 |
| 校验 | Jakarta Validation | `spring-boot-starter-validation` |
| Lombok | 1.18.44 | 编译期生成 getter/setter/构造器/日志对象（`provided` 作用域） |
| MapStruct | 1.6.3 | 编译期生成 Entity ↔ VO 映射实现（无反射、无运行时开销） |
| SpringDoc / OpenAPI | 3.1.1 | 自动生成接口文档与 Swagger UI 调试页 |
| Knife4j | 4.5.0 | Swagger UI 的国产增强界面（`/doc.html`），仅静态资源包 |

## 分层架构

```
┌──────────────────────────────────────────────────────┐
│ 视图层 controller/    参数绑定 · 参数校验 · 响应封装   │
├──────────────────────────────────────────────────────┤
│ 契约层 model/         dto(入参) · vo(出参)             │
├──────────────────────────────────────────────────────┤
│ 服务层 service/       用例编排 · 事务边界 · 业务规则    │
├──────────────────────────────────────────────────────┤
│ 数据层 repository/    entity(实体) · mapper(持久化 SQL) │
├──────────────────────────────────────────────────────┤
│ 通用层 common/ · config/  响应体 · 异常 · 装配配置      │
└──────────────────────────────────────────────────────┘
```

依赖方向严格单向：`controller → service → repository`，倒置需在 `service` 层定义接口。

## 目录结构

> 下面是最早只有用户模块时的骨架，用于说明分层怎么落位。后续按模块横向扩展
> （`controller/`、`service/impl/`、`mapper/` 下每个业务一组文件，`config/` 下是鉴权、菜单权限、
> 日志、链路追踪等横切组件），完整清单见各模块章节末尾的「文件清单」。

```
src/main/java/com/example/erupt/
├── EruptExampleApplication.java         # 启动入口
├── common/                              # 统一响应、错误码、异常、全局异常处理
│   ├── Result.java  ResultCode.java  PageResult.java
│   └── BusinessException.java  GlobalExceptionHandler.java
├── config/MybatisConfig.java            # @MapperScan + 事务管理开关
├── controller/UserController.java       # 视图层：REST 接口
├── model/
│   ├── dto/UserCreateRequest.java       # 入参校验模型
│   ├── dto/UserUpdateRequest.java
│   ├── dto/UserQueryRequest.java
│   └── vo/UserVO.java                   # 出参模型
├── service/
│   ├── UserService.java                 # 服务层接口（可单元测试）
│   └── impl/UserServiceImpl.java        # 事务边界 + 业务规则
└── repository/                          # 数据层
    ├── entity/User.java
    └── mapper/UserMapper.java
src/main/resources/
├── application.yml                      # 端口/MySQL/MyBatis 配置
├── mapper/UserMapper.xml                # SQL 映射
└── db/schema.sql                        # 建库建表脚本
```

## 快速开始

1. 初始化数据库（按顺序执行，均需带字符集，否则中文入库成乱码）：

| 顺序 | 脚本 | 内容 | 是否必需 |
|---|---|---|---|
| 1 | `db/schema.sql` | 建库 + 用户 / 知识库 / 导航等基础表 + 初始账号 | 必需 |
| 2 | `db/schema-im-menu.sql` | 即时通信三张表 + 菜单 / 角色 / 关联表 | 必需 |
| 3 | `db/init-im-menu.sql` | 角色、菜单树、授权初始数据（会先清空 `t_menu`） | 必需 |
| 4 | `db/schema-operation-log.sql` | 系统操作日志表 + 「系统日志」菜单（m21）及其超管授权 | 需要日志功能时 |
| 5 | `db/schema-tools-menu.sql` | 「工具箱」菜单（m22）及其授权——**纯前端页面，不建表、不加接口** | 需要工具箱时 |
| 6 | `db/schema-agent.sql` | 智能体两张表（会话 / 消息）+ 字典种子 `agent_config` + 菜单（m23） | 需要智能体时 |
| 7 | `db/schema-gantt.sql` | 时间规划三张表（计划 / 任务 / 依赖）+ 菜单（m24） | 需要甘特图时 |
| 8 | `db/schema-notification.sql` | 通知中心一张表（t_notification）+ 菜单（m25） | 需要通知中心时 |
| 9 | `db/schema-usage.sql` | 菜单 m26 用量统计 / m27 AI 读库（无新表，聚合 t_agent_message） | 需要这两个功能时 |
| 10 | `db/schema-reminder.sql` | t_notification 加 `biz_id`（事件去重）+ AI 读库审计表 t_agent_data_audit | 需要任务提醒 / 读库审计时 |
| 11 | `db/schema-md-viewer.sql` | 工具箱子菜单 m28（纯前端页面，无新表、无接口） | 需要 MD 在线查看时 |
| 12 | `db/migrate_gantt_dependency_soft_delete.sql` | 老库升级：依赖表加 `del_flag`，唯一键改到生成列 `alive_from_to` | 仅升级已建过甘特图表的库 |
| 13 | `db/migrate_knowledge_index.sql` | 知识库全文索引 `ft_knowledge_search` | 可选（关闭全文检索则不需要） |
| 14 | `db/migrate_id_to_varchar32.sql` | 老库升级：`bigint` 主键转字符串并 `LPAD` 补零 | 仅从旧版本升级时 |

```bash
for f in schema.sql schema-im-menu.sql init-im-menu.sql schema-operation-log.sql schema-tools-menu.sql schema-agent.sql schema-gantt.sql; do
  mysql --default-character-set=utf8mb4 -uroot -p erupt-example < "src/main/resources/db/$f"
done
```

2. 确认配置（`src/main/resources/application.yml`）：

```yaml
server.port: 8080
spring.datasource.url: jdbc:mysql://127.0.0.1:3306/erupt-example?...
spring.datasource.username: root
spring.datasource.password: 12345678910
```

3. 启动与验证：

```bash
mvn -U clean package -DskipTests
java -jar target/erupt-example-0.0.1-SNAPSHOT.jar
# 或
mvn spring-boot:run
```

启动后可访问自动生成的接口文档：

| 地址 | 说明 |
|---|---|
| `http://localhost:8080/doc.html` | **Knife4j 增强界面**（中文，推荐）：分组导航、调试、导出文档 |
| `http://localhost:8080/swagger-ui.html` | 原生 Swagger UI（同为 SpringDoc 生成，二者任选） |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3.1 规范 JSON，可用于生成前端 TS 类型 |

两套界面共用同一份规范（`/v3/api-docs`），数据完全一致，只是展示层不同。

4. 单元测试：

```bash
mvn test
```

## 接口示例

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/users` | 新增用户（201；**密码 BCrypt 入库，不返回实体**，按 insert 影响行数判定成功/失败） |
| PUT | `/api/users/{id}` | 更新用户（部分字段；**不返回实体**，按 update 影响行数判定成功/失败） |
| GET | `/api/users/{id}` | 用户详情（返回实体） |
| GET | `/api/users?keyword=&status=&page=1&pageSize=10` | 分页列表（返回实体列表） |
| DELETE | `/api/users/{id}` | 软删除用户（置 `del_flag='1'`，**不物理删除**；记录不存在返回 404；影响行数为 0 时返回 500） |

统一响应：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { "id": "a1b2c3...", "username": "admin" },
  "timestamp": 1730000000000
}
```

```bash
curl -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"123456","nickname":"管理员","email":"a@b.com","phone":"13800138000"}'
```

### 接口总览（按模块）

全部接口都以 `/api` 开头，统一返回 `Result` 信封；分页接口的 `data` 为 `PageResult`。
**共 12 个视图层、94 个端点**，明细见各模块章节：

| 模块 | 前缀 | 端点数 | 视图层 | 菜单权限标识 | 说明 |
|---|---|---|---|---|---|
| 认证 | `/api/auth` | 7 | `AuthController` | 放行 | 登录、验证码、登出、WebSocket 票据、当前用户 |
| 用户 | `/api/users` | 11 | `UserController` | `users` | 管理端增删改查 + 角色分配；`/me` 三个自助接口放行 |
| 菜单与角色 | `/api/menu` | 11 | `MenuController` | `menu` | 菜单树、角色 CRUD、角色授权；`/mine` 放行 |
| 数据字典 | `/api/dict` | 9 | `DictController` | `dict` | 类型 / 字典项两套 CRUD；**三个读接口放行** |
| 知识库 | `/api/knowledge` | 7 | `KnowledgeController` | `knowledge` | 含批量删除、类型枚举 |
| 导航 | `/api/navigation` | 10 | `NavigationController` | `navigation` | 含批量删除、导入 / 导出、分类 |
| 附件 | `/api/attachments` | 14 | `AttachmentController` | `attachments` | 上传、预览、打包下载、回收站两级删除 |
| 数据库浏览 | `/api/databases` | 4 | `DatabaseController` | `databases` | 只读，走 `information_schema` |
| 即时通信 | `/api/chat` | 8 | `ChatController` | 放行 | 会话、消息、未读、可聊对象 |
| 聊天附件 | `/api/chat/attachments` | 2 | `ChatAttachmentController` | 放行 | 独立存储，不复用 `t_attachment` |
| 系统日志 | `/api/logs` | 1 | `OperationLogController` | `logs` | 只读审计查询，无写接口 |
| 智能体 | `/api/agent` | 4 | `AgentController` | `agent` | GLM 多轮对话；会话列表 / 消息 / 删除；配置在数据字典 |
| 时间规划 | `/api/gantt` | 10 | `GanttController` | `gantt` | 计划 / 任务 / 依赖三套 CRUD；依赖成环校验、父任务进度汇总 |

> 「放行」指不参与菜单授权：`AuthInterceptor` 的匿名白名单只有 3 个（登录 / 登录前置 / 验证码），
> 其余放行都是 `MenuAccessInterceptor` 里的路径规则，见「菜单与授权模块 › 双层防线」。
>
> 另有**工具箱**（`/tools`）不在上表：它是纯前端页面，**没有任何 `/api` 端点**，
> 全部转换在浏览器本地完成，详见「工具箱模块」。

## 认证模块（t_user + Redis 会话）

所有 `/api/**` 请求默认受 `AuthInterceptor` 保护，凭证统一走 `Authorization: Bearer <token>` 请求头。
白名单内的三个匿名端点不需要凭证，其余未带有效 Token 一律返回 **401**。

> **初始账号**：`admin` / `Admin@123456`（BCrypt 强度 10）。
> 由 `resources/db/schema.sql` 末尾的 INSERT 写入，**生产环境首次登录后必须立即修改**。
> 执行脚本请务必带字符集：`mysql --default-character-set=utf8mb4`，否则中文昵称会入库成乱码。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/auth/captcha` | 匿名 | 获取图形验证码 |
| GET | `/api/auth/login-hint` | 匿名 | 查询登录前置状态：是否需要验证码、账号是否被锁定 |
| POST | `/api/auth/login` | 匿名 | 登录，签发 Token |
| GET | `/api/auth/ws-ticket` | 需鉴权 | 为 WebSocket 握手签发**一次性**短期票据（60 秒、用后即焚） |
| GET | `/api/auth/profile` | 需鉴权 | 当前登录用户信息 |
| POST | `/api/auth/logout` | 需鉴权 | 退出登录，吊销服务端会话 |
| POST | `/api/auth/logout-via-param` | 需鉴权 | 同上，改用 `token` 参数传凭证（兼容非标准 header 场景） |

### 1. GET /api/auth/captcha — 获取图形验证码

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `username` | query | 否 | 账号，仅用于日志统计，不影响验证码生成 |

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "captchaKey": "7f3a1c9e4b2d...",
    "image": "data:image/png;base64,iVBORw0KGgo..."
  },
  "timestamp": 1730000000000
}
```

- `captchaKey`：验证码标识，登录时连同答案回传
- `image`：Base64 data URI，可直接赋给 `<img src>`

约定：**答案不放在 URL 上**（避免经反向代理日志泄露），而是生成随机 key 随图片下发；答案存 Redis，TTL 由 `app.auth.captcha-ttl` 控制（默认 2 分钟）；验证码为**一次性消费**，校验通过即删 Redis key，防止重放。

```bash
curl 'http://localhost:8080/api/auth/captcha?username=admin'
```

### 2. GET /api/auth/login-hint — 登录前置状态

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `username` | query | 否 | 待查询的账号；为空或长度不足时返回未锁定状态 |

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { "needCaptcha": false, "retryAfter": 0 },
  "timestamp": 1730000000000
}
```

| 字段 | 说明 |
|---|---|
| `needCaptcha` | 是否要求输入验证码。连续失败 2 次后自动为 `true`；也可由 `app.auth.captcha-required=true` 强制开启 |
| `retryAfter` | 账号剩余锁定秒数，0 表示未锁定 |

用途：账号失焦或登录失败后调用，据此决定是否展示验证码输入框——避免一进页面就打扰正常用户。

```bash
curl 'http://localhost:8080/api/auth/login-hint?username=admin'
```

### 3. POST /api/auth/login — 登录

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `username` | string | 是 | 4~32 位，仅字母、数字、下划线 |
| `password` | string | 是 | 6~64 位（上限是因为 BCrypt 只处理前 72 字节，超长会产生等价类） |
| `captchaKey` | string | 否 | 需要验证码时必填，取自 captcha 接口 |
| `captchaCode` | string | 否 | 同上，≤10 位 |

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "token": "MDEyMzQ1Njc4OWFiY2RlZg.1k3Z...",
    "expiresIn": 7200,
    "user": {
      "id": "0123456789abcdef0123456789abcdef",
      "username": "admin",
      "nickname": "系统管理员",
      "avatar": "",
      "email": "admin@example.com",
      "phone": null,
      "status": 1,
      "remark": null,
      "lastLoginTime": "2026-10-20T16:20:00",
      "createTime": "2026-10-20T15:00:00",
      "updateTime": "2026-10-20T16:20:00"
    }
  }
}
```

- `token`：`base64(userId|username|exp) + "." + base64(HMAC-SHA256(payload, secret))`
- `expiresIn`：有效秒数，前端据此做本地过期判断
- `user`：`UserVO`，**不含 `password` 字段**
- 登录成功会刷新该账号的 `last_login_time`

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123456"}'
```

错误响应：

| 场景 | HTTP | code | message |
|---|---|---|---|
| 账号或密码不正确 | 401 | 401 | `账号或密码不正确` |
| 账号已锁定 | 401 | 401 | `账号已锁定，请稍后再试` |
| 账号已被禁用 | 403 | 403 | `账号已被禁用` |
| 验证码不正确 | 400 | 400 | `验证码不正确` |
| 参数校验失败 | 400 | 400 | 字段级提示（如 `账号长度需为 4~32 个字符`） |

安全约定：

- **防账号枚举**：账号不存在与密码错误返回**完全相同**的提示与耗时——账号不存在时仍执行一次 BCrypt 运算（dummy hash），抹平响应时间差异，避免时序侧信道
- **失败次数限制**：同一账号连续失败达 `app.auth.login-max-failures`（默认 5）次即锁定，锁定时长与计数窗口同为 `app.auth.login-fail-window`（默认 15 分钟）
- **HTTP 状态码与业务 code 会同时为 401**，前端判定时两者任一命中即应视为未登录

### 4. GET /api/auth/profile — 当前登录用户

需带有效 Token。`data` 为与登录一致的 `UserVO`（不含 `password`）。

```bash
curl http://localhost:8080/api/auth/profile \
  -H 'Authorization: Bearer <token>'
```

### 5. POST /api/auth/logout — 退出登录

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `Authorization` | header | 否 | `Bearer <token>` |

```json
{ "code": 200, "message": "退出成功", "data": null, "timestamp": 1730000000000 }
```

服务端直接删除 Redis 会话，该 Token **立即失效**（不等 TTL 自然过期）。

**幂等**：未带 Token、或 Token 已过期，一律返回 200。登出不应在客户端视角呈现"失败"，否则会陷入登不出去的状态。

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H 'Authorization: Bearer <token>'
```

### 6. POST /api/auth/logout-via-param — 退出登录（参数传凭证）

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `token` | query / form | 是 | Token 原文，不带 `Bearer ` 前缀 |

为无法设置请求头的场景（如部分第三方跳转、表单直提）提供的等价退出口，行为与 `/logout` 完全一致。

### 7. GET /api/auth/ws-ticket — WebSocket 一次性票据

无参数，需带有效 Token：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { "ticket": "8f14e45fceea167a5a36dedd4bea2543" },
  "timestamp": 1730000000000
}
```

| 约定 | 值 |
|---|---|
| 有效期 | **60 秒** |
| 使用次数 | **仅一次**（Redis `GETDEL` 原子读删，并发下也只会成功一次） |
| 用途 | 建连时替代长期 Token：`ws://<host>/ws/chat?ticket=<ticket>` |
| 存储 | Redis `auth:ws-ticket:<ticket>` → userId |

**为什么需要它**：浏览器 WebSocket API 不允许自定义请求头，凭证只能挂在 URL 上、必然进访问日志与代理日志。
长期 Token（默认 2 小时）一旦通过日志泄露，在剩余有效期内都能被重放；换成 60 秒且一次性的 ticket 之后，
即使被抓到也基本已经失效。握手时若没有 `ticket`，服务端会回退接受 `token` / `access_token` 参数以兼容旧前端，
新代码一律走 ticket。

### Token 与会话约定

| 环节 | 实现 |
|---|---|
| 签名 | HMAC-SHA256，密钥取自 `app.auth.token-secret`；校验时常量时间比较，防时序攻击 |
| 会话存储 | Redis `auth:token:<sha256(token)>` → userId，TTL 与 Token 有效期一致 |
| 吊销 | 登出即删 Redis key，立即失效 |
| 账号即时禁用 | 每次请求查一次用户表（主键查询），账号被禁用/删除后**下一次请求**即失效 |
| Redis 故障降级 | Redis 不可用时降级为"仅验签"——可用性优先，代价是吊销不再即时 |

### 认证配置项

均位于 `application.yml` 的 `app.auth` 下，支持环境变量覆盖：

| 配置键 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `token-secret` | `AUTH_TOKEN_SECRET` | `erupt-example-dev-secret-change-me-in-production` | **生产必须注入随机值（≥32 字节）**，否则任何人都能伪造 Token |
| `token-ttl` | `AUTH_TOKEN_TTL` | `2h` | Token 有效期 |
| `login-max-failures` | `AUTH_LOGIN_MAX_FAILURES` | `5` | 连续失败达此次数即锁定 |
| `login-fail-window` | `AUTH_LOGIN_FAIL_WINDOW` | `15m` | 失败计数窗口，也是锁定时长 |
| `captcha-ttl` | `AUTH_CAPTCHA_TTL` | `2m` | 验证码有效期 |
| `captcha-required` | `AUTH_CAPTCHA_REQUIRED` | `false` | 是否强制每次登录都要验证码 |
| `enabled` | `AUTH_ENABLED` | `true` | 鉴权总开关，**置 false 会放行所有接口，仅限本地联调** |

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/AuthController.java` |
| 服务层 | `service/AuthService.java`、`service/impl/AuthServiceImpl.java` |
| 凭证服务 | `service/TokenService.java` |
| 验证码服务 | `service/CaptchaService.java` |
| 失败限制 | `service/LoginAttemptService.java` |
| 配置绑定 | `config/AuthProperties.java` |
| 密码编码器 | `config/SecurityConfig.java` |
| 鉴权拦截器 | `config/AuthInterceptor.java` |
| 拦截器注册 | `config/WebMvcConfig.java` |
| 身份上下文 | `common/AuthContext.java` |
| 入参 DTO | `model/dto/LoginRequest.java` |
| 出参 VO | `model/vo/LoginVO.java`、`UserVO.java`、`CaptchaVO.java`、`LoginFailHintVO.java` |
| 初始数据 | `resources/db/schema.sql`（末尾 INSERT） |
| 单元测试 | `src/test/java/com/example/erupt/service/TokenServiceTest.java` |

### 前端登录态与登录页跳转

**已登录访问 `/login` 一律跳回系统内**，不会停在登录表单上。判定依据是本地是否存在
未过期的 token（`getToken()` 会连带校验 `exp`，过期即清理）。两道保障：

| 位置 | 作用 |
|---|---|
| 路由守卫 `router/index.ts` | 主路径，拦截所有指向 `/login` 的导航，用 `replace` 避免"后退"再退回登录页 |
| `LoginView` 的 `onMounted` | 兜底：页面从 bfcache 恢复、或守卫将来被改动时不至于把用户卡在表单上 |

`redirect` 查询参数属于**用户可控输入**，由 `utils/redirect.ts#resolveRedirectTarget` 统一收敛：
外链（`https://…`）、协议相对地址（`//evil.com`）、伪协议（`javascript:`）、
以及指回 `/login` 自身的值一律丢弃并回落到首页。少了这一步就是开放重定向漏洞
（登录成功后被送去钓鱼站），同时也能避免"重定向到登录页"的死循环。

**token 存储**：登录页「记住我」默认勾选，token 写入 `localStorage`，**多个标签页共享登录态**；
取消勾选则写 `sessionStorage`，关掉标签页即失效。

> 这里曾默认 `false`（sessionStorage）。sessionStorage 是**标签页隔离**的，
> 新开标签页打开 `/login` 时读不到 token，只能显示登录表单，
> 表现为"我明明登录着，怎么又要我登"。要让登录态跨标签页生效，凭证就必须放在共享存储中。
> 若安全策略上更看重"关页即失效"，把 `LoginView` 的 `remember` 默认值改回 `false` 即可。


## 知识模块（t_knowledge，库表已存在）

字段映射以 `erupt-example.t_knowledge` 真实结构为准（实体保留列名映射，VO/DTO 对外使用简洁命名）：

| 库表列 | 实体属性 | 对外（DTO/VO） |
|---|---|---|
| `id` | `id` | `id` |
| `knowledge_name` | `knowledgeName` | `name` |
| `knowledge_content` | `knowledgeContent` | `content` |
| `knowledge_type` | `knowledgeType` | `type` |
| `create_user_id` / `update_user_id` | `createUserId` / `updateUserId` | 只读返回；**应用层自动填充**：新增写创建人、更新写更新人（均取 `AuthContext.currentUserId()`，存的是 `t_user.id`），与导航模块口径一致 |
| `create_time` / `update_time` | `createTime` / `updateTime` | 应用层填充 |

文件清单：

| 层级 | 文件路径 |
|---|---|
| 实体 | `repository/entity/Knowledge.java` |
| Mapper 接口 | `repository/mapper/KnowledgeMapper.java` |
| SQL 映射 | `resources/mapper/KnowledgeMapper.xml` |
| 入参 DTO | `model/dto/KnowledgeCreateRequest.java`、`KnowledgeUpdateRequest.java`、`KnowledgeQueryRequest.java` |
| 出参 VO | `model/vo/KnowledgeVO.java` |
| 服务层 | `service/KnowledgeService.java`、`service/impl/KnowledgeServiceImpl.java` |
| 视图层 | `controller/KnowledgeController.java` |
| 结构说明 | `resources/db/schema.sql`（建表语句由库表提供，不重复建表） |
| 单元测试 | `src/test/java/com/example/erupt/service/impl/KnowledgeServiceImplTest.java` |

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge` | 新增知识（类型+标题唯一校验，创建/更新时间应用层填充） |
| PUT | `/api/knowledge/{id}` | 更新知识（部分字段） |
| GET | `/api/knowledge/{id}` | 知识详情（含正文） |
| GET | `/api/knowledge?keyword=&type=&page=1&pageSize=10` | 分页列表（**标题 + 正文**检索，默认走全文索引 `MATCH...AGAINST`；单字/纯操作符关键词自动降级 LIKE） |
| DELETE | `/api/knowledge/{id}` | 删除知识（记录不存在返回 404；影响行数为 0 时返回 500 业务失败） |
| DELETE | `/api/knowledge/batch` | **批量删除**（body：`{"ids":["..."]}`；id 去重后任一不存在则**整批拒绝**返回 404；单次上限 500 条） |
| GET | `/api/knowledge/types` | 查询所有类型（knowledge_type），**去重**、剔除空值、按字典序升序 |

```bash
curl -X POST http://localhost:8080/api/knowledge \
  -H 'Content-Type: application/json' \
  -d '{"name":"如何重置密码","content":"<p>正文</p>","type":"java"}'
```

## 导航模块（t_navigation，库表已存在）

字段映射（实体保留列名映射，VO/DTO 对外使用简洁命名）：

| 库表列 | 实体属性 | 对外（DTO/VO） |
|---|---|---|
| `id` | `id` | `id` |
| `navigation_name` | `navigationName` | `name` |
| `navigation_url` | `navigationUrl` | `url` |
| `navigation_img` | `navigationImg` | `img` |
| `cat` | `cat` | `cat` |
| `desc`（保留字，SQL 需反引号） | `description` | `description` |
| `tag` | `tag` | `tag` |
| `create_user_id` / `update_user_id` | `createUserId` / `updateUserId` | 只读返回；新增/导入写入创建人，单条更新与导入覆盖写入更新人（均为 `t_user.id`）；列表由 LEFT JOIN 带出 `createUserName` |
| `create_time` / `update_time` | `createTime` / `updateTime` | 应用层填充 |

文件清单：

| 层级 | 文件路径 |
|---|---|
| 实体 | `repository/entity/Navigation.java` |
| Mapper 接口 | `repository/mapper/NavigationMapper.java` |
| SQL 映射 | `resources/mapper/NavigationMapper.xml` |
| 入参 DTO | `model/dto/NavigationCreateRequest.java`、`NavigationUpdateRequest.java`、`NavigationQueryRequest.java`、`NavigationImportRequest.java`、`NavigationImportItemRequest.java` |
| 出参 VO | `model/vo/NavigationVO.java`、`NavigationImportResultVO.java`、`NavigationImportErrorVO.java` |
| 服务层 | `service/NavigationService.java`、`service/impl/NavigationServiceImpl.java` |
| 视图层 | `controller/NavigationController.java` |
| 单元测试 | `src/test/java/com/example/erupt/service/impl/NavigationServiceImplTest.java` |

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/navigation` | 新增导航（名称、地址必填；地址限 http/https；不做重名校验） |
| PUT | `/api/navigation/{id}` | 更新导航（部分字段；地址同样限 http/https，不允许清空） |
| GET | `/api/navigation/{id}` | 导航详情 |
| GET | `/api/navigation?keyword=&cat=&tag=&page=1&pageSize=10` | 分页列表（名称/描述模糊 + 分类/标签精确；按 `create_time` 倒序，最新录入在前） |
| DELETE | `/api/navigation/{id}` | 删除导航（记录不存在返回 404；影响行数为 0 时返回 500 业务失败） |
| DELETE | `/api/navigation/batch` | **批量删除**（body：`{"ids":["..."]}`；id 去重后任一不存在则**整批拒绝**返回 404；单次上限 500 条） |
| GET | `/api/navigation/cats` | 查询所有分类（cat），**去重**、剔除空值、按字典序升序 |
| GET | `/api/navigation/cats/all` | 查询所有分类（cat），**不去重**，按 `create_time` 升序（录入顺序） |
| POST | `/api/navigation/import` | **批量导入**（body：`{"items":[...],"mode":"skip\|overwrite"}`；单次上限 1000 行；逐行校验、失败行回传行号与原因，成功行照常入库） |
| GET | `/api/navigation/export?keyword=&cat=&tag=` | **导出**（与列表同一套筛选条件、跨分页；上限 5000 条，超出要求先缩小范围；只回 JSON，Excel 由前端本地生成） |

```bash
curl "http://localhost:8080/api/navigation?cat=%E5%8A%9E%E5%85%ACAI&page=1&pageSize=5"
curl -X POST http://localhost:8080/api/navigation \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nginx UI","url":"https://nginxui.com/","img":"/img/a.jpg","cat":"运维","description":"nginx ui","tag":"nginx ui"}'
```

## 附件模块（t_attachment，本次新建）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/attachments/upload` | 上传单个附件（字段名 `file`，201），返回含 `id` 的附件信息 |
| POST | `/api/attachments/upload/batch` | 上传多个附件（字段名 `files`，可多次追加，201） |
| GET | `/api/attachments/{id}/download` | 下载单个附件，直接返回文件流 |
| GET | `/api/attachments/{id}/preview` | 在线预览，`Content-Disposition: inline`，供 iframe / img / video 渲染 |
| POST | `/api/attachments/download/zip` | 多附件打包下载（body：`{"ids":["..."]}`），返回 ZIP 流 |
| GET | `/api/attachments` | 正常附件分页，支持文件名/分类/时间过滤 |
| GET | `/api/attachments/recycle` | 回收站附件分页，参数同上 |
| POST | `/api/attachments/{id}/restore` | 恢复单个附件（回收站 → 正常） |
| POST | `/api/attachments/restore/batch` | 批量恢复（body：`{"ids":["..."]}`） |
| DELETE | `/api/attachments/{id}` | 删除单个附件（移入回收站，可逆） |
| DELETE | `/api/attachments/batch` | 批量删除（批量移入回收站，body：`{"ids":["..."]}`） |
| DELETE | `/api/attachments/recycle/{id}` | 彻底删除单个（不可逆，连磁盘文件一起清除） |
| DELETE | `/api/attachments/recycle/batch` | 批量彻底删除 |
| DELETE | `/api/attachments/recycle` | 清空回收站 |

预览有两条约定，踩过一次就会明白为什么不能偷懒：

- **接口地址不能直接交给标签**。`/preview` 在鉴权之后，而 `<img src>`、`<iframe src>` 发出的请求不带 `Authorization`，直连只会拿到 401——表现是图片裂图、PDF 一片空白、文本报"读取失败"。前端统一用 `http.getBlob` 取回二进制再转 object URL（`src/api/attachment.ts` 的 `previewBlob`），关闭弹窗时 `revokeObjectURL` 释放。
- **HTML / Markdown 渲染的是效果，不是源码**。后端对可脚本化文本（HTML、SVG、JS、CSS）一律降级成 `text/plain` 输出，避免有人直接打开下载链接时执行脚本；前端拿到字节后自行重建 `text/html` Blob，放进 `sandbox=""` 的 iframe 渲染——不透明源加禁脚本，既有排版效果，又不给上传的 HTML 任何执行机会。Markdown 用 `marked` 转成 HTML 后走同一条沙箱链路（`src/utils/markdown.ts`），并保留"效果 / 源码"切换。

阈值均配置在 `application.yml`（统一以 **KB** 为单位）：

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `app.attachment.storage-dir` | `./uploads` | 文件存储根目录 |
| `app.attachment.max-upload-size-kb` | `51200` | 单个文件上传上限（KB，即 50 MB），0 表示不限制 |
| `app.attachment.zip-max-total-size-kb` | `51200` | 打包下载总大小阈值（KB） |

超过打包阈值时返回 **400** `多个附件下载超大，不允许打包下载`，且判定发生在**打包之前**，不会产出残缺 ZIP。
注意 `spring.servlet.multipart.max-file-size` 必须大于上面的上传上限，否则请求会先在容器层被拦下，业务层的友好提示不会生效。

### 分页查询与回收站的行为约定

分页接口支持以下可选过滤条件：`originalName`（文件名模糊）、`fileType`（分类精确）、`startTime` / `endTime`（上传时间区间，格式 `yyyy-MM-dd HH:mm:ss`）。
响应统一为 `{ items, total, page, pageSize }`。

| 情况 | 行为 |
|---|---|
| `page` / `pageSize` 为 0、负数或不传 | **自动修正**为 1 / 10，不报错 |
| `pageSize` 超过 100 | 收敛到上限 100 |
| **页码超出实际页数** | 返回**空 items + 真实 total**，HTTP 200，前端据此跳回合理页 |
| 偏移量超过 100000 | 拒绝，400（深分页会拖垮数据库） |
| `startTime` 晚于 `endTime` | 拒绝，400 |
| `fileType` 非取值范围内 | 拒绝，400，并回显合法取值 |

恢复操作采用「**整批通过才执行**」：单个恢复给的 id 不存在 → 404，存在但不处于回收站 → 409；批量恢复时任一条不满足则**一条都不恢复**。

删除分两级，这是防误删的关键：**先移入回收站（可逆）**，再对回收站里的附件做**彻底删除（不可逆，连同磁盘文件清除）**。后端保证「正常状态的附件无法被彻底删除」——直接调用彻底删除接口会得到 409，必须先移入回收站。移入回收站同样采用「整批通过才执行」：任一条不存在或已在回收站，则一条都不移。回收站的删除会等数据库事务提交后再清理物理文件，顺序上宁可留下孤儿文件，也不留下指向不存在文件的记录。

回收站保留天数由 `app.attachment.recycle-retention-days` 配置（默认 30 天），写入 `recycle_expire_time` 供前端提示到期时间；到期清理任务不在本模块实现。

**所有写操作都要校验数据库返回的受影响行数**：上传入库、恢复、移入回收站、彻底删除、清空回收站，都会拿实际影响行数与预期值比对，不符即判定业务失败、事务回滚，响应码 500——不会返回"部分成功"这种无法判断结果的状态。接口提示信息里的条数（如"已恢复 2 个附件"）是数据库实际影响行数，不是请求传入的条数。

唯一的例外是下载计数自增：它同样是写操作、同样判断返回状态，但只是旁路统计，失败只记告警，不会把一次已经成功写出文件的下载判为失败。

前端通用调用示例（含错误处理）见 [docs/attachment-frontend-example.md](docs/attachment-frontend-example.md)。

## 数据字典模块（t_dict / t_dict_item，本次新建）

两层结构：字典类型（如「用户状态」）下挂若干字典项（启用=1 / 禁用=0）。两表通过 `dict_type` 业务字段关联，不加外键，与 `t_role_menu` 的约定一致。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dict/types` | 类型分页，`keyword` 同时匹配编码与名称 |
| POST | `/api/dict/types` | 新建类型（201），编码限字母/数字/下划线 |
| PUT | `/api/dict/types/{id}` | 编辑类型（不含编码，编码不可改） |
| DELETE | `/api/dict/types/{id}` | 删除类型，连带软删除其下全部字典项 |
| GET | `/api/dict/items` | 字典项分页，按 `dictType` 过滤；为空返回空页 |
| GET | `/api/dict/{dictType}/items` | 按类型编码取全部**可用**字典项：不分页、按 `sort_num` 升序，排除已删除与已禁用项（下拉框 / 搜索栏专用） |
| POST | `/api/dict/items` | 新建字典项（201），同类型下 `value` 不可重复 |
| PUT | `/api/dict/items/{id}` | 编辑字典项（不含 `dictType`） |
| DELETE | `/api/dict/items/{id}` | 删除字典项（软删除） |

几条设计约定：

- **软删除**：字典项的值会被业务历史数据引用（存的是 `value` 而非主键），物理删除会让那些历史值变成无法解释的字符串，因此只置 `del_flag='1'`。
- **编码唯一用生成列实现**：`t_dict.dict_type_active` 只在未删除行上等于 `dict_type` 并参与唯一约束，这样软删除后同一编码可以重建，沿用 `t_user.username_active` 的既有做法。
- **`value` 是 MySQL 保留字**，Mapper 里所有出现处都加了反引号。
- **下拉框与分页列表是两个接口**：`GET /api/dict/{dictType}/items` 只给"能选"的项（未删除且 `status=1`），而分页列表 `GET /api/dict/items` 必须能看到已禁用的项，否则管理员没法在页面上把它重新启用——所以两者的过滤条件刻意不同，下拉框也不要用分页接口传一个大 `pageSize` 去"猜"全量。
- 菜单挂在「系统管理」下（`system_dict`，权限标识 `dict`）。因权限拦截按 URI 第二段判断，本组接口默认受该菜单授权控制；**但三个读接口对全部登录用户开放**（`GET /api/dict/types`、`GET /api/dict/items`、`GET /api/dict/{dictType}/items`），无需授予「数据字典」菜单，只有写操作（新增/编辑/删除）才需要该授权。

## 数据库模块（information_schema 只读浏览）

全部为 GET，不写任何数据、不建任何表——纯粹借 `information_schema` 系统视图实现"看看本机有哪些库 / 哪些表 / 表里有什么数据"。所有端点均在 `/api/**` 鉴权范围内，需带有效 Token。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/databases` | 列出本机所有数据库（含系统库，由 `systemSchema` 标识） |
| GET | `/api/databases/{schema}/tables` | 指定库的表分页列表，支持表名 / 表注释模糊搜索 |
| GET | `/api/databases/{schema}/tables/{table}/columns` | 指定表的列元信息 |
| GET | `/api/databases/{schema}/tables/{table}/data` | 表数据分页预览 |

### 1. GET /api/databases — 数据库列表

无参数，`data` 为数组：

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaName` | string | 库名 |
| `charset` | string | 默认字符集 |
| `collation` | string | 默认排序规则 |
| `tableCount` | number | 表数量 |
| `totalSizeBytes` | number | 数据 + 索引总字节数 |
| `systemSchema` | boolean | 是否系统库（`information_schema` / `mysql` / `performance_schema` / `sys`） |

```bash
curl http://localhost:8080/api/databases -H 'Authorization: Bearer <token>'
```

### 2. GET /api/databases/{schema}/tables — 表列表

| 参数 | 位置 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `schema` | path | 是 | - | 库名 |
| `keyword` | query | 否 | - | 模糊匹配 `TABLE_NAME` **或** `TABLE_COMMENT` |
| `page` | query | 否 | 1 | 页码 |
| `pageSize` | query | 否 | 10 | 每页条数 |

`data` 为 `{ items, total, page, pageSize }`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaName` | string | 库名 |
| `tableName` | string | 表名 |
| `tableComment` | string | 表注释 |
| `tableRows` | number | 行数，**取自 `information_schema` 的统计值**，InnoDB 下为估算值非精确值 |
| `dataLength` | number | 数据字节数 |
| `indexLength` | number | 索引字节数 |
| `engine` | string | 存储引擎 |
| `collation` | string | 排序规则 |
| `createTime` | string | 建表时间 |

### 3. GET /api/databases/{schema}/tables/{table}/columns — 列元信息

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `schema` | path | 是 | 库名 |
| `table` | path | 是 | 表名 |

`data` 为数组，每项字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 列名 |
| `type` | string | 简化类型（`varchar` / `int` 等） |
| `columnType` | string | 完整类型（含长度，如 `varchar(100)`） |
| `comment` | string | 列注释 |
| `nullable` | string | `YES` / `NO` |
| `columnKey` | string | 索引类型（`PRI` / `UNI` / `MUL`） |
| `defaultValue` | any | 默认值 |
| `extra` | string | 扩展信息（如 `auto_increment`） |
| `ordinalPosition` | number | 列序号，从 1 开始 |

### 4. GET /api/databases/{schema}/tables/{table}/data — 表数据预览

| 参数 | 位置 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `schema` | path | 是 | - | 库名 |
| `table` | path | 是 | - | 表名 |
| `page` | query | 否 | 1 | 页码 |
| `pageSize` | query | 否 | 10 | 每页条数 |

`data` 结构：

| 字段 | 类型 | 说明 |
|---|---|---|
| `columns` | array | 列元信息，结构同上一节的数组项 |
| `rows` | array | 数据行，每项为 `{ 列名: 值 }` 的键值对 |
| `total` | number | 总行数 |
| `page` / `pageSize` | number | 当前页码 / 每页条数 |

```bash
curl 'http://localhost:8080/api/databases/erupt-example/tables/t_user/data?page=1&pageSize=10' \
  -H 'Authorization: Bearer <token>'
```

### 安全约定

- **库名与表名一律走命名白名单校验**（`requireValidName`），不通过则返回 400——标识符无法用预编译参数绑定，只能这样防注入
- `keyword` 使用 `ESCAPE '/'` 转义 LIKE 通配符，避免 `%` / `_` 被当成模式匹配
- 分页偏移量与其它模块一致设上限，超限返回 400（深分页会拖垮数据库）
- 所有 SQL 均只查 `information_schema` 与指定表的只读 SELECT，不含任何写操作

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/DatabaseController.java` |
| 服务层 | `service/DatabaseService.java`、`service/impl/DatabaseServiceImpl.java` |
| Mapper | `repository/mapper/DatabaseMapper.java`、`resources/mapper/DatabaseMapper.xml`（**无对应实体**） |
| 出参 VO | `model/vo/DatabaseVO.java`、`TableVO.java`、`TableDataVO.java` |

## 即时通信模块（t_chat_conversation / t_chat_member / t_chat_message）

已登录用户之间的即时消息。全部聊天记录持久化入库，支持服务端主动推送新消息提醒。

### 技术方案

**WebSocket（原生，非 STOMP）+ MySQL 持久化**。

为什么这么选：

| 候选 | 判断 |
|---|---|
| HTTP 轮询 | 做不到"消息发出后对方**立刻**收到"；轮询间隔就是延迟下限，还得持续浪费请求 |
| SSE（EventSource） | 服务端→客户端单向可行，但发消息仍要另走 HTTP；等于维护两条通道 |
| **WebSocket** | 全双工、一次握手长期复用，适合双向聊天。启用方式见 `websocket/WebSocketConfig.java` |

未采用 STOMP 的理由：它要求前端额外引入 stomp.js 并引入消息代理语义，而本项目只需要"按 userId 定向推送"这一种模式，原生 Handler + 会话注册表即可胜任，少一层协议少一层出错点。

多实例扩展：当前用内存注册表（`ChatSessionRegistry`）维护在线连接，适用于单实例。若将来多实例部署，把发送动作换成"发布到 Redis 频道 + 各实例订阅后本地投递"即可，上层业务代码无需改动。

### 数据表

| 表 | 作用 |
|---|---|
| `t_chat_conversation` | 会话。单聊用 `biz_key`（双方用户ID升序拼接）保证同一对用户只有一个会话 |
| `t_chat_member` | 会话成员，冗余存 `unread_count`，避免每次 COUNT 消息表 |
| `t_chat_message` | 消息本体，聊历史记录与未读提醒的唯一数据源 |

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chat/conversations` | 我的会话列表（含对方昵称头像、未读数、最后一条消息） |
| POST | `/api/chat/conversations/with/{userId}` | 与某人建立或复用单聊会话 |
| GET | `/api/chat/conversations/{id}/messages` | 历史消息分页 |
| POST | `/api/chat/messages` | 发送消息（落库并推送） |
| POST | `/api/chat/conversations/{id}/read` | 标记已读 |
| GET | `/api/chat/unread` | 未读概览（按发送人聚合） |
| GET | `/api/chat/unread/total` | 未读总数 |
| GET | `/api/chat/partners?keyword=` | 可发起聊天的用户；`keyword` 对 `username` / `nickname` 做模糊匹配，`%`、`_` 已转义 |
| POST | `/api/chat/attachments` | 上传聊天附件（图片或文件，`multipart`，字段 `file`） |
| GET | `/api/chat/attachments/{id}` | 读取聊天附件（上传者本人或会话成员） |

WebSocket 端点：`ws://<host>/ws/chat?ticket=<ticket>`。

建连流程是「先取票、再握手」：

```
GET /api/auth/ws-ticket   （带 Authorization: Bearer <token>）→ { ticket }
        │
        └─ new WebSocket('ws://<host>/ws/chat?ticket=' + ticket)
```

**为什么不是直接把 Token 拼在 URL 上**：浏览器 WebSocket API 不允许自定义请求头，`Authorization` 加不上去，
凭证只能进查询串、必然落进访问日志与代理日志。长期 Token 泄露后可在剩余有效期内重放，
因此改用 60 秒有效、只能用一次的 ticket（见「认证模块 › 7. ws-ticket」）。
握手拦截器仍接受 `token` / `access_token` 参数，但那只是兼容旧前端的退路，新代码不应再使用。

推送报文格式：

```json
{
  "type": "unread",
  "total": 2,
  "items": [
    { "senderId": "...", "senderName": "系统管理员", "unreadCount": 2,
      "lastContent": "最新一条消息", "lastTime": "2026-10-21T21:58:00", "conversationId": "..." }
  ]
}
```

### 消息类型（文本 / 图片 / 文件）

`t_chat_message.msg_type`：1 文本、2 图片、3 文件、4 系统通知（系统通知暂未使用）。

附件消息分两步（先上传、再发消息），而不是一次 multipart 提交：

1. `POST /api/chat/attachments`（字段名 `file`，图片 ≤10MB、其它文件 ≤20MB）→ 返回 `{ id, name, size, contentType }`
2. `POST /api/chat/messages`，body 带 `attachmentId`；`msgType` 可省略，服务端按 MIME 推断为 2（图片）或 3（文件）

`content` 对附件消息存 `[图片]` / `[文件]` 而不是空：会话列表的"最近一条"直接读 `content`，留空会让列表显示一行空白，看起来像消息丢了。

#### 与文件管理完全独立

聊天附件**不复用** `t_attachment`，也不写 `./uploads`：

| | 文件管理 | 聊天附件 |
|---|---|---|
| 数据表 | `t_attachment` | `t_chat_attachment` |
| 存储目录 | `./uploads` | `./chat-uploads`（与前者平级，不嵌套） |
| 鉴权口径 | `attachments` 菜单权限 | 登录即可上传，按会话成员判定读取 |
| 能力 | 列表、回收站、批量打包 | 随消息读写，无回收站 |

共用的后果是双向的：聊天里发的图会出现在文件列表里；文件列表清空回收站，又会顺手带走聊天记录里的图。分开之后两边互不可见、互不可删。

代价是聊天附件另有一套上传与流式输出实现（`ChatAttachmentService`），百来行代码——这个重复是刻意的，换来两个模块各自演进时不会互相绊倒。

#### 为什么附件不能直接写进 `<img src>` / `<a href>`

读取接口要求身份（上传者本人或会话成员），而浏览器发起的图片、链接请求不会携带 `Authorization` 头，直接写地址必然 401/403。前端 `ChatImage.vue`、`ChatFile.vue` 都先用带凭证的请求取回二进制：图片转 object URL 渲染（卸载时 `revokeObjectURL`，否则持续吃内存），文件直接触发下载。

#### 权限判定

- **写**：`requireAttachment` 校验 `uploader_id`，只能引用自己上传的附件。少了这条，拿到别人的附件 id 就能把自己的会话当跳板读走它，下面的读规则就形同虚设
- **读**：上传者本人可读（刚上传、消息还没发出时要能预览自己选的图），其余只放行"引用了该附件的会话成员"

附件 id 一旦泄露（截图、日志、分享链接），不能再靠"它不好猜"来兜底。

#### 响应头

图片按 `inline` 返回，其余一律 `Content-Disposition: attachment`，并附 `X-Content-Type-Options: nosniff`——不能靠"把 html 改名成图片"让脚本在本站域下执行。附件内容按 id 固定不变，另加 `Cache-Control: private, max-age=3600`，避免滚动聊天记录时反复拉流。

### 数据库变更

```sql
-- 消息引用附件
ALTER TABLE t_chat_message
    ADD COLUMN attachment_id varchar(32) DEFAULT NULL COMMENT '附件ID（图片/文件消息）' AFTER content,
    ADD KEY idx_attachment (attachment_id);

-- 聊天附件独立表（与 t_attachment 无关，完整 DDL 见 resources/db/schema-im-menu.sql）
CREATE TABLE IF NOT EXISTS `t_chat_attachment` (
    `id` varchar(32) NOT NULL,
    `original_name` varchar(255) NOT NULL,
    `file_ext` varchar(32) DEFAULT NULL,
    `content_type` varchar(128) DEFAULT NULL,
    `file_size` bigint NOT NULL DEFAULT 0,
    `storage_path` varchar(512) NOT NULL,
    `uploader_id` varchar(32) NOT NULL,
    `del_flag` char(1) NOT NULL DEFAULT '0',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_uploader` (`uploader_id`),
    KEY `idx_create_time` (`create_time`)
) COMMENT '聊天附件（与文件管理 t_attachment 相互独立）';
```

> 上面是建表当时的原始 DDL（`schema-im-menu.sql` 里也是 32 位）。主键与引用列后来统一加宽到 `varchar(64)`，
> 以实际库为准，见文末「约定说明」。

配置项（`application.yml`）：

```yaml
app:
  attachment:
    storage-dir: ./uploads        # 文件管理
  chat:
    attachment:
      storage-dir: ./chat-uploads # 聊天附件，按日期分子目录：<root>/yyyy/MM/dd/<id>.<ext>
```

**目录刻意平级而不是嵌套**：运维的备份、清理、迁移脚本通常以附件根目录为界，聊天附件若放在 `./uploads/xxx` 下就会被顺带波及，"分开"只剩名义。启动时会校验聊天目录不在文件管理目录之内，配错直接启动失败并给出明确提示，避免这种错配在日志里被忽略：

```
IllegalStateException: 聊天附件目录 /app/uploads/chat 位于文件管理目录 /app/uploads 内，两者必须互不包含
```

### 消息流转

```
发送方 HTTP POST /api/chat/messages
    │
    ├─ 写 t_chat_message（诉求持久化）
    ├─ 更新会话摘要 last_msg_id / last_msg_time
    ├─ 累加接收方 t_chat_member.unread_count
    │
    └─ 【事务提交之后】经 WebSocket 推送给接收方在线连接
              │
              └─ 接收方右下角弹出提示 → 点击进入会话 → markRead → 计数清零
```

**推送刻意安排在事务提交之后**（`TransactionSynchronization.afterCommit`）。若在事务内推送，接收方收到通知后会立刻拉取未读列表，而此时消息尚未提交，会看到"收到提醒但没有新消息"的诡异状态。

未登录用户的情况：上线时服务端主动推一次未读概览（`afterConnectionEstablished`），因此离线期间积攒的消息会在登录后一次性弹出，无需离线队列。

### 安全约定

- 会话访问强制校验成员身份（`requireMember`），伪造 conversationId 读取他人聊天记录会返回 403
- 握手阶段即鉴权，无效连接不占用任何业务资源
- 一条 userId 可对应多个连接（多标签页/多设备），每个都会收到推送
- 心跳 30 秒一次，断线按 2/4/8/16 秒指数退避重连，凭证失效即停止重试

### 聊天附件的已知限制

- 聊天附件存本地 `./chat-uploads`（`app.chat.attachment.storage-dir`），多实例或容器化部署需挂载共享卷或换对象存储；该目录与 `./uploads` 需分别纳入备份
- 聊天附件没有回收站：消息删除后文件不会自动清理（当前也还没有删消息接口），长期运行需要配一个按时间归档的任务
- 删除消息目前没有对应接口；若将来实现，记得同步重算 `t_chat_conversation.last_msg_id`，否则会话列表会指向一条不存在的消息、预览变空
- 文件大小上限写在后端常量与前端校验两处（图片 10MB / 文件 20MB），改动时要一起改

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/ChatController.java`、`controller/ChatAttachmentController.java` |
| 服务层 | `service/ChatService.java`、`service/impl/ChatServiceImpl.java` |
| 附件服务 | `service/ChatAttachmentService.java`、`service/impl/ChatAttachmentServiceImpl.java`（独立存储，不经过 `AttachmentService`） |
| WebSocket | `websocket/ChatWebSocketHandler.java`、`ChatSessionRegistry.java`、`ChatHandshakeInterceptor.java`、`WebSocketConfig.java` |
| Mapper | `repository/mapper/ChatMapper.java`、`ChatAttachmentMapper.java`、`resources/mapper/ChatMapper.xml`、`ChatAttachmentMapper.xml` |
| 实体 | `repository/entity/ChatConversation.java`、`ChatMember.java`、`ChatMessage.java`、`ChatAttachment.java` |
| VO / DTO | `model/vo/ConversationVO.java`、`MessageVO.java`、`ChatAttachmentVO.java`、`UnreadSummaryVO.java`、`model/dto/SendMessageRequest.java` |
| 前端 | `src/api/chat.ts`（上传 / 取流）、`src/components/ChatImage.vue`（图片预览）、`ChatFile.vue`（文件卡片 + 下载）、`src/views/chat/ChatView.vue`（选图 / 选文件 / 粘贴 / 气泡分流） |
| 建表与初始数据 | `resources/db/schema-im-menu.sql` |


### 在线状态（presence）

单聊会话标题栏显示的是**对方**是否在线。数据来自服务端内存中的连接注册表
（`ChatSessionRegistry#isOnline`），**不落库**——在线是瞬时事实，存进数据库只会立刻过期。

两条通道配合，保证看到的就是当前真相：

| 通道 | 作用 |
|---|---|
| 会话列表字段 `peerOnline` | 打开页面 / 刷新列表时给出当前快照 |
| WebSocket `presence` 帧 | 对端上线、下线时**立即**推送，无需轮询 |

广播时机经过刻意收敛：只在"该用户的第一条连接建立"和"最后一条连接断开"时发出。
一个人可能同时开着多个标签页或设备，关掉其中一个并不代表他离开了。

> **两个状态不要混淆**：会话标题栏的"在线 / 离线"说的是**对方**；
> 会话列表顶部的"本机已连接 / 未连接"说的是**自己**与推送通道的连接情况。
> 这两者曾经被挤在同一个位置、共用同一份数据（当前用户的连接状态），
> 导致"对方已退出却仍显示实时连接"的误判。

`presence` 帧格式：

```json
{ "type": "presence", "userId": "user01aaaa...", "online": false }
```


## 菜单与授权模块（t_menu / t_role / t_user_role / t_role_menu）

用户分级 + 菜单授权。用户仅能看到并访问被授权的菜单，未授权页面不可达。

### 多级菜单设计方案

库里只存**扁平记录 + `parent_id` 自关联**，树在应用层组装。顶级菜单的 `parent_id` 为 `'0'`，同级靠 `sort_num` 排序。

这样设计的原因：若冗余 `level` / 层级码这类字段，一旦移动某个父节点，它所有子孙的冗余值都要批量刷新，极易写漏。宁可查询时在内存里组装一次树，也不维护会腐烂的派生数据。

层级深度不设限制。"建多级菜单"在接口上只体现为一个参数：

| parentId 取值 | 结果 |
|---|---|
| `"0"` 或不传 | 顶级菜单 |
| 某个已有菜单的 ID | 成为该菜单的子菜单 |

移动子树因此只需改一个 `parent_id`。删除时若存在子节点会被拒绝（避免产生孤儿菜单），更新时也会拒绝把父节点指向自己或自己的子孙（防止成环）。

### 数据表

| 表 | 作用 |
|---|---|
| `t_menu` | 菜单。`menu_type`：1 目录 / 2 菜单 / 3 按钮；`permission` 是与 API 模块名对应的权限标识 |
| `t_role` | 角色，即**用户分级**。`role_level` 数字越小权限越高（0 为超级管理员） |
| `t_user_role` | 用户 ↔ 角色，多对多 |
| `t_role_menu` | 角色 ↔ 菜单，多对多 |

### 授权模型

```
用户 ──多对多──▶ 角色 ──多对多──▶ 菜单
```

用户**不直接**绑菜单。好处是给一批人开通相同权限时只维护一次角色；角色本身的 `role_level` 还可用于扩展数据范围控制。

### 双层防线

只隐藏菜单等于没做权限——用户可以直接构造请求。因此两处同步收紧：

| 层 | 位置 | 拦截什么 |
|---|---|---|
| 前端体验层 | `router/index.ts` 守卫 + 侧边栏按授权树渲染 | 用户看不见、手敲地址会被路由拦下跳 403 |
| **服务端边界** | `config/MenuAccessInterceptor` | 按 `permission` 校验 API 可达性，未授权返回 403 |

服务端拦截器依赖菜单表的 `permission` 字段与 URI 模块名对应：`/api/knowledge/xxx` → permission `knowledge`。**初始化数据时务必给业务菜单配上 permission**，未配置的模块对所有登录用户开放。

放行的路径分四类，全部在 `MenuAccessInterceptor` 里逐条列举（前缀 / 精确路径 / 正则，不做宽泛匹配）：

| 类别 | 路径 | 为什么放行 |
|---|---|---|
| 认证 | 前缀 `/api/auth/` | 未登录用户必须能登录 |
| 聊天 | 前缀 `/api/chat/` | 所有登录用户的基础能力，不参与菜单授权 |
| 自助与渲染前提 | 精确 `/api/menu/mine`、`/api/users/me`、`/api/users/me/password` | 取自己的菜单是渲染前提；看 / 改**自己的**资料是基础能力。纳入模块鉴权会形成死锁或功能死链 |
| 只读共享数据 | `GET /api/dict/types`、`GET /api/dict/items`、`GET /api/dict/{dictType}/items` | 各业务页都要用字典把存储值翻成展示名。**只放行 GET**，字典的写操作仍受 `dict` 菜单保护 |

未列出的模块一律按 URI 第二段校验，例如 `/api/logs` → `logs`、`/api/attachments/upload` → `attachments`；
未授权即 403（`无权限访问该菜单`）。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/menu/tree` | 全量菜单树（管理端） |
| GET | `/api/menu/mine` | **当前用户**被授权的菜单树 |
| POST | `/api/menu` | 新建菜单（parentId 指认层级） |
| PUT | `/api/menu/{id}` | 编辑菜单 |
| DELETE | `/api/menu/{id}` | 删除菜单（有子菜单时拒绝） |
| GET | `/api/menu/roles` | 角色列表 |
| POST | `/api/menu/roles` | 新建角色 |
| PUT | `/api/menu/roles/{id}` | 编辑角色 |
| DELETE | `/api/menu/roles/{id}` | 删除角色（超级管理员角色不可删） |
| GET | `/api/menu/roles/{roleId}/menus` | 查询角色已授权菜单 |
| POST | `/api/menu/roles/{roleId}/menus` | 给角色授权菜单（全量覆盖） |
| GET | `/api/users/{id}/roles` | 查询用户拥有的角色（**已迁移**，见下方"用户管理模块"） |
| POST | `/api/users/{id}/roles` | 给用户分配角色（全量覆盖，**已迁移**） |

> 用户-角色接口原先挂在 `/api/menu/users/{userId}/roles`。由于 `MenuAccessInterceptor` 按 URI
> 第二段判断所属权限模块，它会被算作 **menu 权限**——于是"有权管理用户却无权给用户分配角色"，
> 权限口径错位。已迁至 `/api/users/{id}/roles`，归入 users 模块，与"用户管理"菜单一致。

授权采用**全量覆盖**（先删后插）而非增量 diff：授权页提交的是"最终应该拥有哪些菜单"，覆盖式写入天然幂等，重复提交不会产生副作用。

### 初始数据

执行 `resources/db/schema-im-menu.sql` 后，需再执行初始化数据脚本（`resources/db/init-im-menu.sql`）：

| 项 | 内容 |
|---|---|
| 角色 | `ROLE_SUPER_ADMIN`（等级 0）、`ROLE_USER`（等级 10） |
| 菜单树 | 20 个节点（`m01`~`m20`），覆盖概览 / 知识库 / 导航 / 文件 / 数据库 / 接口文档 / 即时通信 / 系统管理 |
| 授权 | 超级管理员授权全部 20 项；普通用户仅 4 项（概览、知识库、即时通信） |

两个后续脚本会**追加**菜单，各自独立、可单独执行：

| 脚本 | 追加菜单 | 默认授权 |
|---|---|---|
| `db/schema-operation-log.sql` | `m21` 系统日志（`permission=logs`，挂在系统管理下） | 仅超级管理员；普通用户访问 `/api/logs` 会 403，需在角色管理里手工分配 |
| `db/schema-tools-menu.sql` | `m22` 工具箱（`permission=tools`，顶级菜单 `/tools`） | 超级管理员 + 普通用户（通用工具，无敏感数据） |
| `db/schema-agent.sql` | `m23` 智能体（`permission=agent`，顶级菜单 `/agent`） | 超级管理员 + 普通用户（按需在角色管理里收回，避免全员消耗 API 额度） |

验证效果（实测）：超级管理员可见全部菜单；按初始脚本，普通用户默认 6 个节点
（概览、知识库、即时通信、工具箱、智能体 + 对应父目录），可用「角色管理」按需追加。
普通用户访问 `/api/knowledge` 放行，访问 `/api/databases` 或 `/api/menu/tree` 返回 403（均不在其授权内）。

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/MenuController.java` |
| 服务层 | `service/MenuService.java`、`service/impl/MenuServiceImpl.java` |
| 拦截器 | `config/MenuAccessInterceptor.java`（注册于 `config/WebMvcConfig.java`，order=2 排在身份校验之后） |
| Mapper | `repository/mapper/MenuMapper.java`、`resources/mapper/MenuMapper.xml` |
| 实体 | `repository/entity/Menu.java`、`Role.java`、`UserRole.java`、`RoleMenu.java` |
| VO / DTO | `model/vo/MenuVO.java`、`RoleVO.java`、`model/dto/MenuRequest.java`、`RoleRequest.java` |
| 建表与初始数据 | `resources/db/schema-im-menu.sql`、`resources/db/init-im-menu.sql` |


## 用户管理模块（t_user）

系统登录账号的增删改查、密码管理与角色分配。前端页面位于系统管理 › 用户管理。

### 接口

分为"管理员操作"与"自助"两类，两者的鉴权口径刻意不同：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/users` | users 权限 | 分页列表，支持 `keyword`（账号/昵称模糊）与 `status` 过滤 |
| POST | `/api/users` | users 权限 | 新增账号 |
| GET | `/api/users/{id}` | users 权限 | 用户详情 |
| PUT | `/api/users/{id}` | users 权限 | 编辑资料（**不含密码**） |
| DELETE | `/api/users/{id}` | users 权限 | 软删除（置 `del_flag='1'`） |
| PUT | `/api/users/{id}/password` | users 权限 | 管理员重置密码，不需要原密码 |
| GET | `/api/users/{id}/roles` | users 权限 | 查询该用户拥有的角色 |
| POST | `/api/users/{id}/roles` | users 权限 | 分配角色（全量覆盖，幂等） |
| GET | `/api/users/me` | **登录即可** | 当前登录用户的资料 |
| PUT | `/api/users/me` | **登录即可** | 修改自己的资料 |
| PUT | `/api/users/me/password` | **登录即可** | 修改自己的密码 |

### 为什么个人信息接口对所有登录用户放行

这是"管理员权限"与"自助能力"的分界。

若把 `/api/users/me` 也纳入 users 菜单鉴权，未获该菜单授权的用户连**自己的**资料页都打不开——
登录成功却看不到个人信息，属于功能死链。用户「看自己」不涉及他人数据，不构成权限问题，
因此在 `MenuAccessInterceptor` 中以精确路径放行。

放行的同时也守住了边界：

- 身份一律取自 Token 解析结果，**不接受任何入参**，从根上排除"改个 id 就能看别人"的越权
- `PUT /api/users/me` **不接收 `status` 与 `remark`**：`status` 能让被管理员禁用的账号把自己改回启用（越权），
  `remark` 是管理员备注。服务端显式忽略，而不依赖前端不传

### 密码为什么单独一条 SQL

`UserMapper#updateById` 是选择性更新，且**不含 `password` 列**。早期改密曾复用它，
结果影响了 `update_time` 使行数 ≥1，接口返回"修改成功"而密码从未变更——一个不报错的静默失败。

因此凭据字段走独立的 `updatePasswordById` 语句：既保证真正写库，也避免任何通用更新顺带改写密码。

密码相关约定：

| 场景 | 是否校验原密码 | 说明 |
|---|---|---|
| 自助改密 | 是 | 防止会话被劫持后直接把密码改掉、锁定账号 |
| 管理员重置 | 否 | 管理员本就无从得知用户密码，要求它等于让功能不可用 |

> 已知限制：后端目前不具备"按 userId 吊销全部会话"的能力（会话按 Token 维度存 Redis，无 userId 索引）。
> 因此改密或重置后，已签发的 Token 在其自然过期前仍然有效。若需要下线保障，应改为维护 user → token 集合。

### 新增账号务必同时分配角色

新账号若不带任何角色，登录后拿不到任何菜单，路由守卫会把所有业务页面重定向到 403——等于建了一个废账号。
因此前端把"角色"设为必选：新增后立即调用角色分配接口，确保账号创建出来就能用。

### 请求 / 响应示例

```bash
# 分页查询
curl 'http://localhost:8080/api/users?keyword=zhang&status=1&page=1&pageSize=10' \
  -H 'Authorization: Bearer <token>'

# 新增（返回 201）
curl -X POST http://localhost:8080/api/users \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"username":"wangwu","password":"Wangwu@123","nickname":"王五","email":"w@example.com"}'

# 给该用户分配角色（全量覆盖）
curl -X POST http://localhost:8080/api/users/<userId>/roles \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '["r_user"]'

# 管理员重置密码
curl -X PUT http://localhost:8080/api/users/<userId>/password \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"newPassword":"Reset@123456"}'

# 自助修改密码（需原密码）
curl -X PUT http://localhost:8080/api/users/me/password \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"oldPassword":"Reset@123456","newPassword":"SelfChg@123"}'
```

分页响应结构沿用全局 `PageResult`：`{ code, message, data: { items, total, page, pageSize }, timestamp }`。

### 错误响应

| 场景 | HTTP | code | message |
|---|---|---|---|
| 账号已存在 | 409 | 409 | `账号已存在：xxx` |
| 用户不存在 | 404 | 404 | `用户不存在：xxx` |
| 参数校验失败 | 400 | 400 | 字段级提示（如 `密码长度需为 6~64 个字符`） |
| 原密码不正确 | 401 | 401 | `原密码不正确` |
| 无 users 权限访问管理接口 | 403 | 403 | `无权限访问` |
| 路径不存在 | 404 | 404 | `接口不存在，请检查请求路径` |

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/UserController.java` |
| 服务层 | `service/UserService.java`、`service/impl/UserServiceImpl.java` |
| Mapper | `repository/mapper/UserMapper.java`、`resources/mapper/UserMapper.xml`（含独立的 `updatePasswordById`） |
| 入参 DTO | `model/dto/UserCreateRequest.java`、`UserUpdateRequest.java`、`UserQueryRequest.java`、`ChangePasswordRequest.java`、`ResetPasswordRequest.java` |
| 出参 VO | `model/vo/UserVO.java`（不含 password） |
| 前端页面 | `views/system/UserManageView.vue`（管理）、`views/profile/ProfileView.vue`（自助） |


## 系统日志模块（t_operation_log）

`/api/**` 请求的审计流水：**谁**（操作人 / IP / TraceId）、对**哪个模块**做了什么（模块 / 操作类型 / URI）、
**结果如何**（状态码 / 耗时）。由 `OperationLogInterceptor` 在请求收尾时**异步**落库，
不参与业务事务——写日志失败只记一条 warn，绝不影响请求结果。

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/logs` | `logs` 权限 | 分页查询，**只读**：没有任何写接口，审计记录不允许通过 API 修改或删除 |

查询参数：

| 参数 | 说明 |
|---|---|
| `keyword` | 模糊匹配 URI / 操作人账号 / IP / TraceId（`%`、`_` 已转义） |
| `module` | 模块**精确**匹配，取值即 URI 第二段（`auth`、`knowledge`、`logs`…） |
| `operation` | 操作类型**精确**匹配，取值见下方推导表 |
| `result` | `1` 成功、`0` 失败；不传表示全部 |
| `startTime` / `endTime` | `yyyy-MM-dd HH:mm:ss`，均为闭区间；`startTime` 晚于 `endTime` 返回 400 |
| `page` / `pageSize` | 默认 `1` / `10`；`pageSize` 上限 `100`（超出收敛到 100）；偏移量超过 100000 拒绝，400 |

响应沿用全局 `PageResult`：`{ code, message, data: { items, total, page, pageSize }, timestamp }`，
`items` 直接是实体字段（不含任何敏感信息，另包一层等字段的 VO 没有意义）。

```bash
# 查一段时间内的失败请求（URL 里的空格需编码）
curl 'http://localhost:8080/api/logs?result=0&startTime=2026-11-04%2016:00:00&endTime=2026-11-04%2017:00:00&page=1&pageSize=20' \
  -H 'Authorization: Bearer <token>'
```

### 记什么、不记什么

| 情况 | 是否记录 | 原因 |
|---|---|---|
| `POST` / `PUT` / `DELETE` | 一律记录（成功失败都记） | 写操作审计价值最高 |
| `GET` 且状态码 < 400 | **不记录** | 成功查询量大、无审计价值；全记会让表膨胀得比业务数据还快 |
| `GET` 且状态码 ≥ 400 | 记录（`result=0`） | 失败的查询正是排查入口 |
| `/api/auth/captcha`、`/api/auth/login-hint` | 不记录 | 高频匿名端点，纯噪音 |
| 请求体（body） | **从不记录** | 登录、改密、建账号的 body 里有密码，审计库不该成为敏感信息的第二份存储；查询串足够定位请求 |

**已知盲区**：拦截器链短路时（`AuthInterceptor` 返回 401、`MenuAccessInterceptor` 返回 403），
排在 order=3 的本拦截器 `afterCompletion` 不会被调用，这类请求不产生日志。
换 `Filter` 能全覆盖，但拿不到 `AuthContext`（它的存活期只覆盖拦截器链），操作人会全部为空——
取舍后保留拦截器方案，代价是「未登录就被拒的请求查不到」。

这与**登录失败**不冲突：登录请求本身走完了整条链，失败会正常落一条 `operation=login, result=0`。

### 模块与操作类型的推导

`module` 取 `/api/` 之后的那一段（`/api/knowledge/list` → `knowledge`），与菜单 `permission` 同一套命名，
因此日志里的模块名可以直接对上权限标识。`operation` 先匹配特殊 URI，再按 HTTP 方法兜底：

| operation | 触发条件 |
|---|---|
| `login` / `logout` | URI 以 `/api/auth/login`、`/api/auth/logout` 开头 |
| `upload` / `download` | URI 含 `upload`；或含 `download` / `/zip` |
| `create` | `POST` |
| `update` | `PUT`、`PATCH` |
| `delete` | `DELETE` |
| `query` | `GET` |
| `other` | 以上都不命中 |

推导只服务于检索归类，不追求语义精确——例如 `POST /api/knowledge/batch` 是批量删除，仍会被记成 `create`。

### 表结构与写入方式

建表与菜单见 `db/schema-operation-log.sql`，字段：
`id`（`varchar(64)`）、`module`、`operation`、`request_method`、`request_uri`、`query_string`（截断 512）、
`operator_id`、`operator_name`、`operator_ip`（优先取 `X-Forwarded-For` 首段，截断 64）、
`result`、`status_code`、`cost_ms`、`trace_id`、`create_time`。

**写入用「单线程 + 有界队列」而不是同步 insert**：日志落在每条请求的收尾路径上，同步写等于给所有接口
额外加一次库操作（数据源忙时还要排队拿连接）。请求线程只做一次无锁 `offer`（队列容量 2000），
由一条守护线程顺序落库；**队满即丢弃并计数**，每丢 100 条告警一次；停机时把队列剩余日志冲刷落库。
审计允许极小概率丢样本，不允许拖慢业务。

三点设计取舍：

- **只增不改**：没有 `update_time`、没有 `del_flag`，也没有清理接口。历史归档交给运维脚本（按 `create_time` 分批导出后删除），应用侧不提供删除入口——能被 API 删掉的审计就不是审计。
- **索引按查询形状建**：`idx_result_time(result, create_time)` 对应最高频的「只看失败 + 时间区间」，`idx_module_operation` 对应模块 / 类型筛选，`idx_operator` 对应按人追溯。
- **`trace_id` 与日志文件的 `%X{traceId}` 同源**（`TraceIdFilter` 生成，同时写入响应头 `X-Trace-Id`），拿它去日志平台搜能直接定位到同一次请求。上游若传来合法的 `X-Trace-Id`（`[A-Za-z0-9._-]{1,64}`）则沿用，便于跨服务串联。

### 前端

页面在「系统管理 › 系统日志」（`views/system/OperationLogView.vue`），只读，没有新增 / 编辑 / 删除入口：

- 筛选含时间快捷段（最近 1 小时 / 今天 / 7 天 / 30 天），支持 30 秒自动刷新（页面切后台、抽屉打开时暂停）；
- 失败记录在结果列用红点标出，表格工具栏汇总「本页失败 N 条」；
- 整行可点开详情抽屉，请求路径 / 查询参数 / TraceId / 完整地址均可一键复制。

> `/api/logs` 已在 `.env` 的 `VITE_MOCK_BYPASS` 白名单内：日志是真实审计数据，mock 里没有也不该有假数据。
> 新增接口要接真实后端时记得同步这份白名单，否则页面会报「mock 未匹配到接口」。

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/OperationLogController.java` |
| 服务层 | `service/OperationLogService.java`、`service/impl/OperationLogServiceImpl.java`（单线程 writer + 有界队列） |
| 拦截器 | `config/OperationLogInterceptor.java`（注册于 `config/WebMvcConfig.java`，order=3） |
| 链路追踪 | `config/TraceIdFilter.java` |
| Mapper | `repository/mapper/OperationLogMapper.java`、`resources/mapper/OperationLogMapper.xml` |
| 实体 / DTO | `repository/entity/OperationLog.java`、`model/dto/OperationLogQueryRequest.java` |
| 建表与菜单 | `resources/db/schema-operation-log.sql` |
| 前端 | `views/system/OperationLogView.vue`、`api/log.ts` |
| 单元测试 | `src/test/java/com/example/erupt/config/OperationLogInterceptorTest.java`、`service/impl/OperationLogServiceImplTest.java` |


## 工具箱模块（纯前端，无后端接口、无数据表）

一个页面装七个日常小工具，全部在浏览器里算完：

| 工具 | 输入 → 输出 | 说明 |
|---|---|---|
| JSON 格式化 | JSON → 美化 / 压缩 | 解析失败给出行列号；缩进可选 2 空格 / 4 空格 / 压缩成一行 |
| JSON ↔ YAML | 双向 | 基于 `js-yaml`，配置文件搬运的常用通道 |
| JSON 转 TS 类型 | JSON 样例 → `export interface …` | 嵌套对象自动生成子接口并按结构去重；中文键名加引号并留原键注释 |
| Base64 编解码 | 文本 ↔ Base64 | UTF-8 安全（中文不乱码）；非法输入明确报错而不是吐乱码 |
| URL 编解码 | 文本 ↔ `%E4%BD%A0…` | `+` 按空格处理 |
| 时间戳转换 | 秒 / 毫秒 ↔ 日期 | 同时给本地时间与 UTC ISO 两种形态 |
| UUID 生成 | — → v4 UUID | 一次 1 / 5 / 10 / 50 个 |

### 为什么不做成后端接口

转换是无状态的纯计算，搬到服务端只会多三样东西：一次网络往返、一份可能带业务数据的请求日志、
以及一个需要鉴权的接口。放在浏览器里，**用户贴进来的 JSON（可能含手机号、密钥、内部报文）根本不出这台机器**。

`t_menu` 里那一行菜单只用于侧边栏显隐与前端路由守卫——`MenuAccessInterceptor` 只拦 `/api/**`，
本页没有任何 `/api` 调用，所以 `permission=tools` 不涉及任何接口鉴权，也不需要配 mock 穿透。

### 交互约定

- **输入即转换**（250ms 防抖），没有「转换」按钮；解析失败直接显示中文原因，JSON 还会换算成行列号
- **空输入不报错**：刚进页面或切到别的工具时显示空态，而不是先甩一条红字
- **上屏**：把输出搬回输入，支持链式转换（先格式化 → 再转 YAML → 再生成 TS 类型）
- **状态按工具隔离**：每个工具的输入与选项分别保存，来回切换不互相覆盖；统一持久化在 `localStorage`，刷新不丢
- 复制走 `navigator.clipboard`，非安全上下文（http）自动退回 `execCommand`

### 加一个工具怎么改

只动 `config/dataTools.ts`：在 `DATA_TOOLS` 里加一项（`id / name / desc / category / icon / options / run`），
左侧导航与工作区自动适配，`ToolsView.vue` 零改动。`run` 是纯函数，失败时 `throw new Error(中文原因)` 即可。

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 页面 | `views/tools/ToolsView.vue` |
| 工具注册表 | `config/dataTools.ts`（声明式描述：选项、示例、执行函数） |
| 纯转换函数 | `utils/converters.ts`（不碰 DOM、不发请求） |
| 路由 | `router/index.ts`（`/tools`，`name: tools`） |
| 菜单与授权 | `resources/db/schema-tools-menu.sql` |
| 新增依赖 | `js-yaml@4` + `@types/js-yaml`（仅 YAML 互转用到） |

实测（2026-11-05）：七个工具全部通过——坏 JSON 报「第 1 行第 3 列附近」、YAML 双向互转、
TS 生成含嵌套 `author: Author`、UUID 10 条均为合法 v4、时间戳秒级往返、Base64 中文往返一致、
URL 解码还原中文；移动端 390px 无横向溢出、导航转为横向滚动条。


## 智能体模块（t_agent_conversation + t_agent_message）

对接智谱开放平台（BigModel）的 GLM-5.3 / GLM-5.3-Flash，走 **OpenAI Chat Completion 协议**：

```
POST https://open.bigmodel.cn/api/paas/v4/chat/completions
Authorization: Bearer <API Key>
{ "model": "glm-5.3", "messages": [ {role, content} … ], "stream": false }
```

### 配置放在数据字典里

| 位置 | 字典类型 | 配置项（`label` = 项名，`value` = 值） | 默认值 |
|---|---|---|---|
| 系统管理 › 数据字典 | `agent_config` | `API Key` | 空——**必须自己填**，未填时对话会直接提示去哪里配 |
| | | `chat_url` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| | | `default_model` | `glm-5.3` |
| | | `system_prompt` | 「你是 ERUP 管理系统内置助手…」，清空则不发 system 消息 |

三个设计点：

- **实时读取**：每轮对话都查一次字典（走 `idx_dict_type_sort_num` 索引，成本可忽略），改完即生效，不用重启；
- **不写死 Key**：代码里没有任何 API Key 常量，也没有从 `application.yml` 读——运营改配置不需要碰代码和发布流程；
- **注意暴露面**：字典的三个读接口对**所有登录用户**放行（各业务页都要用它把存储值翻成展示名），
  所以 `agent_config` 里的 API Key 对任何登录用户都是可读的。这不是本模块引入的问题，
  但把密钥放进字典会放大它——若要收紧，可改成只给超管授权并加一个「仅超管可读的类型白名单」，
  或在 Key 旁标注「仅本系统内部使用，泄露即换」。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/chat` | 发一条消息（非流式，一次返回全文）；`conversationId` 不传 = 新建会话；可带 `attachmentIds` |
| POST | `/api/agent/chat/stream` | 流式对话（SSE）：正文与思考过程逐块下发；参数 / 配置错误在建立流之前以普通 JSON 返回 |
| GET | `/api/agent/conversations` | 当前用户的会话列表（最近对话在前，最多 100 条） |
| GET | `/api/agent/conversations/{id}/messages` | 某会话全部消息，时间正序（含每条消息的附件元信息） |
| DELETE | `/api/agent/conversations/{id}` | 软删除会话 |

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"content":"用一句话解释什么是数据库索引"}'
```

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "conversationId": "9f2c…", "title": "用一句话解释什么是数据库索引",
    "userMessage": { "id": "…", "role": "user", "content": "用一句话解释什么是数据库索引",
                     "attachments": [] },
    "reply": { "id": "…", "role": "assistant", "content": "…Markdown…",
               "reasoning": "…思考过程（可空）…", "model": "glm-5.3",
               "promptTokens": 96, "completionTokens": 210, "costMs": 8342 }
  }, "timestamp": 1793859315077
}
```

> `userMessage` 是落库后的用户消息：前端插入的乐观气泡要用它替换，
> 因为「附件是否被解析」（`attachments[].parsed`）只有服务端知道。

### 流式输出（正文为什么要一块一块吐）

`stream:false` 时上游把整篇回答攒齐才返回，页面只能干等——GLM-5.3 的思考模式始终开启，
写一篇千字长文要一两分钟，用户看到的就是「一直在思考，然后突然出现整篇」。
`/api/agent/chat/stream` 改为 `stream:true` 边收边转发：

| SSE 事件 | data | 含义 |
|---|---|---|
| `reasoning` | `{"text":"增量"}` | 思考过程增量，页面显示在「思考中…」折叠块里 |
| `delta` | `{"text":"增量"}` | 正文增量，页面按到达顺序拼进同一条气泡 |
| `done` | `{"conversationId","title","userMessage","reply"}` | 流结束：落库后的完整结果（id / tokens / 耗时），页面用它替换流式气泡 |
| `error` | `{"message":"提示"}` | 流建立后的失败（空回复、上游错误、超时） |

四个实现要点：

- **增量包一层 JSON**：模型输出里的换行若直接写进 `data:` 行，空行会被解析成事件结束，
  前端只拿到半句话；`{"text":"…"}` 让换行变成 `\n` 转义。
- **读上游用 JDK HttpClient**：`RestClient` 是「读完整响应体」的模型，拿不到行流；
  `HttpResponse.BodyHandlers.ofLines()` 才能逐行读 `data:`。
- **SSE 跑独立线程池**：`SseEmitter` 必须异步写，否则会一直占着 Tomcat 请求线程；
  4 并发，满了直接提示「稍后再试」而不是无界排队。
- **落库与非流式共用 `prepareChat` + `persistAssistant`**：上下文组装、附件解析、
  消息写入只有一份实现，流式回答与刷新后回放不会出现两套结果。

### 多轮对话怎么实现

API 是无状态的，「上下文」必须每轮重新带上。本项目的做法：

1. `user` 消息先落库（`t_agent_message`）；
2. 从消息表取该会话**最近 20 条**（`MAX_CONTEXT_MESSAGES`），反转成正序；
3. 若字典配了 `system_prompt`，拼在 messages 首位；
4. 整包发给上游，`assistant` 回复落库并刷新会话 `update_time`（列表按它倒序）。

前端**不回传历史**——历史由后端从库里取，避免前端伪造 `assistant` 角色注入上下文，
也保证「页面刷新后上下文还在」。

### 附件怎么处理

**先说边界：GLM-5.3 官方文档明确「目前仅支持处理文本模态信息」，不能把图片当视觉输入。**
硬塞 `image_url` 内容块只会让请求失败，所以这里不做「假装能看图」的事：

| 附件类型 | 处理方式 |
|---|---|
| 文本类（`text/*`、json / xml / yaml / 代码 / csv / md …，按 MIME + 扩展名双判定） | 后端读正文拼进本轮 user 消息，包在 `===== 附件《name》开始/结束 =====` 之间；单文件 ≤ 32KB、单轮总计 ≤ 96KB，超出截断并注明 |
| 图片 / PDF / Office / 压缩包等 | 只存档：消息里可见、可点开预览，但正文不进上下文；给模型追加一句「用户还附带了 xxx，你无法读取其内容，不要臆测」，避免它顺着文件名编造 |

上传复用**现有**附件接口（`POST /api/attachments/upload`，未做任何改动），前端拿到 `id` 后随消息传
`attachmentIds`；文件名 / 类型 / 大小由服务端按 id 查库，不信任前端。附件正文**不落库**（否则历史回显与
上下文都会重复膨胀），消息表只存元信息 JSON；多轮组装时历史轮只标注「本轮附带附件：a、b」。
界面上「未解析」的小徽标就是 `parsed=false` 的可视化——它表示模型没看到内容，不是上传失败。

### 错误码怎么翻译

上游是「HTTP 状态码 + 业务码」两层，直接抛给使用者没有意义，统一翻译到「该做什么」的粒度：

| 上游信号 | 本系统的提示 |
|---|---|
| HTTP 401 / 业务码 1000、1001、1003 | 智谱 API 认证失败：去数据字典检查 API Key |
| 业务码 1211 | 模型不存在：检查 `default_model` 取值 |
| 业务码 1261 | 对话过长：新建会话或删旧会话 |
| 业务码 1301 | 内容被平台安全审核拦截 |
| HTTP 429（含 1113 欠费、1302 限流、1308/1310 额度） | 触发限流或额度限制：稍后重试 + 查余额 |
| HTTP 5xx / 1200 / 1230 / 1234 | 上游暂时不可用：稍后重试 |
| 网络层异常 / 读超时 | 连接智谱平台失败或超时 |

**上游 401 被故意映射成 HTTP 500 返回**：前端的全局 401 处理会清 token 并跳登录页，
若把上游认证失败当成 401 抛出，用户会被莫名登出。

另有两处兼容：`<think>…</think>` 混在正文里时自动拆到 `reasoning` 字段；
响应按 String 接收再用 Jackson 2 解析（Boot 4 的 HTTP 转换器是 Jackson 3，
直接 `body(JsonNode.class)` 会因两套 Jackson 的 `JsonNode` 不通用而失败）。
超时设置：连接 5s、读 180s——GLM-5.3 的思考模式始终开启，复杂问题一轮几十秒是常态。

### 表结构

| 表 | 作用 | 关键字段 |
|---|---|---|
| `t_agent_conversation` | 一行 = 一次多轮对话 | `user_id`（归属，会话仅本人可见）、`title`（首条消息前 50 字）、`model`、`del_flag` |
| `t_agent_message` | 一行 = 一条 user / assistant 消息 | `conversation_id`、`role`、`content`（MEDIUMTEXT，存 Markdown 原文）、`attachments`（TEXT，附件元信息 JSON）、`reasoning`、`model`、`prompt_tokens` / `completion_tokens` / `cost_ms` |

`attachments` 只存 `[{id,name,contentType,size,parsed}]` 这样的引用，文件本体仍归附件模块的
`t_attachment` 管理——同一份文件在文件管理页也能看到，不做第二份存储。

消息表**不设** `del_flag`：会话软删除后整段不可达，消息跟随会话生命周期；
正文存 Markdown 原文不做 HTML 转换——渲染形态交给展示层。

两张表的 `create_time` / `update_time` 用 **`DATETIME(3)` 毫秒精度**：一轮对话里 user 与 assistant
两条消息常常在同一秒内落库，秒级精度会让 `ORDER BY create_time` 的顺序随机——
上下文顺序错了，模型看到的就是「先回答后提问」。毫秒精度 + `NOW(3)` 写入解决这个问题。

### 前端

页面在「智能体」（`views/agent/AgentView.vue`）：

- 左侧会话列表（新建 / 切换 / 删除，最近对话在前），**会话栏顶部右侧的「收起」按钮可折叠**，收起后入口平移到对话区头部，不会出现「关了打不开」（折叠状态记忆在 `localStorage`）；移动端会话列表收进顶部下拉；
- 输入区是一个 3 行起的大输入框（`textarea`），底部工具条：左边附件按钮，右边模型选择 + **圆形上箭头发送按钮**（空输入 / 上传中自动禁用）；
- 附件：选图后立刻出本地缩略图（`objectURL`），文本类附件上传完成后随消息发送；历史消息里的图片走预览接口取 `Blob` 再转 `objectURL`（`<img src>` 无法带 `Authorization` 头）；
- 助手回复用 `marked` 渲染 Markdown、`DOMPurify` 消毒后 `v-html`——模型输出属于不可信输入，直接渲染等于把 XSS 注入权交给上游；
- 思考过程（`reasoning`）折叠展示；每条回复下方显示「模型 · tokens · 耗时」；
- 等待时显示「正在思考… Ns」计时（非流式，长回答需要耐心）；出错显示红色气泡，会话与已输入内容不丢，可直接重发；
- Enter 发送 / Shift + Enter 换行，输入法组合期间回车不会误发（`isComposing` 判断）。

### 文件清单

| 层级 | 文件路径 |
|---|---|
| 视图层 | `controller/AgentController.java` |
| 服务层 | `service/AgentService.java`、`service/impl/AgentServiceImpl.java`（上游调用 + 错误翻译 + 上下文组装） |
| Mapper | `repository/mapper/AgentMapper.java`、`resources/mapper/AgentMapper.xml` |
| 实体 / DTO / VO | `repository/entity/AgentConversation.java`、`AgentMessage.java`、`model/dto/AgentChatRequest.java`、`model/vo/AgentChatVO.java`、`AgentConversationVO.java`、`AgentMessageVO.java`、`AgentAttachmentVO.java` |
| 建表与种子 | `resources/db/schema-agent.sql`（两张表 + `agent_config` 字典 + 菜单 `m23`） |
| 附件 | 复用 `AttachmentService.getResource(id)` 只读取正文，**不改任何附件接口** |
| 前端 | `views/agent/AgentView.vue`、`api/agent.ts`；路由 `/agent` |
| 新增依赖 | 后端无（用 Spring 自带的 `RestClient`）；前端 `dompurify`（Markdown 消毒） |

实测（2026-11-05）：菜单可见、四个接口就位；未配 Key 时返回
「尚未配置 API Key：请到「系统管理 › 数据字典」编辑 agent_config 下的「API Key」」，
配假 Key 时上游 401 被翻译为认证失败提示（HTTP 500，未触发登出），
非法模型名被白名单拦下（400）；多轮上下文实测第 1/2/3 轮分别收到 2/4/6 条消息，角色序列正确；
附件实测：文本附件正文被完整读入并包在分隔标记里、图片附件收到「无法读取其内容」的说明、
消息表 `attachments` 元信息与 `parsed` 标记正确、刷新后历史图片仍能显示；
浏览器实测会话栏折叠、大输入框、圆形发送按钮、模型在发送按钮左侧、上传进度与「未解析」徽标均正常，
移动端 390px 无横向溢出。**真实对话需在数据字典里填入你的 API Key**。

流式实测（2026-11-06）：一轮对话在 8.09s–10.88s 之间下发 232 个事件块（reasoning 175 块 + delta 56 块），
`done` 里的落库正文与增量拼接逐字一致（106 字），tokens / 耗时齐全；
页面正文长度按 0→23→96→139→153 递增，确认是渐进渲染而不是一次吐出；
思考阶段点「停止」不产生空白回复，正文已输出后停止则已生成部分照常落库（前端 91 字 / 库中 101 字，
差值来自断开瞬间仍在途的分片）；离开页面会主动断流，不让上游白跑。


## 全局搜索 + 通知中心（第 1 步）

两个模块互为配套：搜索解决「找内容」，通知解决「被推着看」。表结构与菜单见 `db/schema-notification.sql`。

### 全局搜索（Ctrl+K）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/search?q=&limit=` | 跨模块联合搜索，每类最多 limit（默认 5）条，按模块分组返回 |

覆盖五个模块：知识库（标题+正文）、AI 导航（名称/描述/标签）、文件（文件名/备注）、时间计划、甘特任务。
可见性与各模块列表页**完全一致**：前三者是共享数据、所有登录用户同结果；
甘特按数据范围隔离（非超管只搜自己创建的计划，任务的归属跟随其所属计划）——
搜索给出的结果，用户点过去一定能看到。

设计要点：

- **`/api/search` 不挂菜单**：全局搜索是基础能力而非某个菜单的功能，挂 `MenuAccessInterceptor`
  的放行清单（登录即可用），理由同 `/api/users/me`。
- **每模块一条 SQL 各自 LIMIT**，服务层合并——不写 UNION 大 SQL：五个模块的
  「按各自 update_time 取最新 N 条」语义在 UNION 里没法各自生效。
- **单字关键词直接不搜**：LIKE '%单字%' 命中率高、价值低、五路全表扫还慢；
  前端同步拦截（≥2 字符才发请求）。

### 通知中心（t_notification + m25）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notifications` | 分页（type / readFlag 可选），未读排最前 |
| GET | `/api/notifications/unread-count` | 未读数（铃铛角标） |
| PUT | `/api/notifications/{id}/read` | 标记单条已读 |
| PUT | `/api/notifications/read-all` | 全部已读，返回实际条数 |
| DELETE | `/api/notifications/{id}` | 软删除 |

事件接入方式：任何模块调 `NotificationService.notify(userId, title, content, type, bizPath)`
即可完成「落库 → 未读数回查 → WebSocket 推送」全链路。当前已接入的事件：

- **智能体回复完成**（type=agent）：回复耗时超过 10 秒才通知——短回答用户还在页面上等，
  每条都推是骚扰；第 3 步将接入任务提醒（type=task）。

推送复用即时通信的 `/ws/chat` 连接（`ChatSessionRegistry`），新增帧
`{"type":"notification","unread":N,"notification":{...}}`——一条连接按 type 分发，
不开第二条 WebSocket；推送挂在事务提交后（`afterCommit`），不会出现「通知先到、数据未提交」。

四个实现要点：

- **归属字段是 `user_id`（接收人）**，不是 create_by——通知是「推给接收人」的数据；
  所有查询在 SQL 层带 userId 条件，归属校验即查询本身。
- **未读计数走 (user_id, read_flag, create_time) 联合索引**，O(未读数) 而非 O(总数)。
- **前端轮询 60s 兜底**：WS 断线重连期间角标不能永远停在旧值。
- **通知中心页面（/notifications）与顶栏抽屉分工**：抽屉只放最近 20 条做「快速看一眼」，
  完整历史、类型筛选、删除走页面。

实测（2026-11-06）：搜索「数据库」命中知识库 2 条、「验证」命中超管可见的甘特计划；
智能体 600 字回复（约 40s）完成后未读数变 1，通知 type=agent、bizPath=/agent；
手写 WS 客户端实测通知帧在回复完成瞬间（0.0s）到达，unread 数随帧携带；
页面级验证：通知页行数、抽屉列表、全部已读后角标隐藏、Ctrl+K 弹层分组命中均正常。


## 智能体用量统计 + AI 读库（第 2 步）

菜单见 `db/schema-usage.sql`（m26 用量统计 / m27 AI 读库），不建新表。
两个功能的 API 都挂在 `/api/agent/*` 下：URI 第二段是 agent，拦截器按「智能体」菜单鉴权——
能用智能体就能查自己的用量；页面菜单用独立 permission（agent-usage / ai-data），
超管可在角色管理里单独关掉这两个入口而不影响智能体本身。

### 用量统计（m26）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/usage?scope=me\|all&trendDays=30` | 汇总 + 模型分布 + 逐日趋势 +（超管）用户排行 |

设计要点：

- **数据源是 t_agent_message 的 assistant 行**——每次真实上游调用一条记录，
  prompt_tokens / completion_tokens / cost_ms / model 都在行上，聚合即可，无需埋点；
- **消息没有归属列**：统计 JOIN 会话表按 create_by 过滤（与「会话只对创建者可见」一致）；
- **已删除会话的消耗也计入**：token 是真实花掉的，「用量」不随会话删除而消失；
- **scope=all 仅超管生效**：普通用户传 all 静默降级为自己的数据；响应带 `canViewAll`
  告知前端是否显示视角切换（曾出过「切换按钮只在全员视图下可见」的反逻辑 bug）；
- **趋势图补零**：30 天窗口无消耗的天由后端补零行，图表时间轴才连续。

### AI 读库（m27，Text-to-SQL 只读版）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/ask-data` | `{question}` → AI 生成 SQL → 校验 → 只读执行 → SQL + 结果原样返回 |

流程：读取当前库 `information_schema` 的表结构概览（缓存 5 分钟，剔除日志表）→
连同问题喂给 GLM（temperature=0，只输出一条 SELECT）→ 三道防线 → 返回。

三道防线（缺一不可）：

1. **形态校验**：只允许单条 SELECT / WITH 开头；剥 markdown 围栏；多语句拒绝；
   写关键词（INSERT/UPDATE/DELETE/DROP/…）用 `\b` 词边界匹配——`update_time` 列名不会误杀；
2. **强制 LIMIT**：语句没有 LIMIT 自动补 `LIMIT 100`，行数上限由系统决定，不能由 AI 决定；
3. **连接只读**：`setReadOnly(true)` + `setMaxRows(100)` + 15s 查询超时，校验漏网也执行不了写操作。

两个原则：

- **SQL 原样展示**：可审计是底线，用户要能看到「AI 用什么语句查出了这个结果」；
- **prompt 里的 `%` 要转义**：`formatted` 把 `LIKE '%关键词%'` 的 `%关` 当成了格式符
  （`UnknownFormatConversionException: Conversion = '关'`），必须写 `%%`——已踩过一次的坑。

实测（2026-11-06）：超管汇总 3 会话 / 20 回复 / 42,577 tokens / 223.6s，glm-5.3-flash 与 glm-5.3
分布正确，用户排行与全员切换正常；AI 读库四个问题全部命中（用户数=7、文档数=13、
最近导航、按模型统计），SQL 合理、执行 13-39ms；「删除所有导航」被 AI 自觉转为 SELECT、
「drop table t_user」被形态校验拦截；页面卡片 / 趋势 / 排行 / SQL 折叠块 / 结果表渲染正常。


## 任务提醒 + AI 读库审计（第 3 步）

迁移见 `db/schema-reminder.sql`：t_notification 加 `biz_id`（事件去重锚点）+
新表 `t_agent_data_audit`（AI 读库审计快照）。

### 任务到期提醒（type=task）

| 入口 | 说明 |
|---|---|
| 定时 | 每日上午 9 点自动扫描（`@Scheduled`，主类 `@EnableScheduling`） |
| 手动 | `POST /api/gantt/reminders/run`（仅超管）——「提醒没发出来？」不用等到第二天上午 |

规则（在 SQL 层实现，`GanttMapper.selectTasksDueReminder`）：

- **提醒范围**：未完成（status != 'done'）且 end_date 在今天 ~ +3 天内（含已逾期与今天到期）；
- **去重**：同一任务（biz_id + type=task）3 天内发过通知就不再返回，走 (biz_id, type) 索引——
  `biz_id` 是本次迁移给 t_notification 加的列，biz_path 只存路由、title 是给人看的文本，
  都不适合做精确匹配，只有业务对象 id 是稳定锚点；
- **停发**：任务 status 变 done 自动停发；用户删掉提醒通知后仍可能再收到——
  软删记录不参与去重，删掉提醒等于要求换条新的再提醒；
- **接收人是计划创建者**（p.create_by）：任务的 assignee 是自由文本、不保证对得上用户表；
- **溢出保护**：单轮 LIMIT 200，`runOnce` 加 synchronized 防手动触发与定时扫描并发双发。

### AI 读库审计快照（t_agent_data_audit）

ask-data 的成功与失败都落一条快照：谁问了什么（原文）、AI 生成了什么 SQL、
结果多少行、成功与否与拦截原因。**没有失败记录，「被拦截」和「没问过」就无法区分**。

- 只增不改：无 update_time / del_flag，Mapper 里根本没有 update/delete 方法，
  唯一入口 `GET /api/agent/data-audit`（仅超管，分页）——审计落库失败只记 warn 不影响主流程；
- 前端入口在用量统计页的超管区块（canViewAll 控制，前端不自己判断角色）。

### 踩坑：跨表 JOIN 的排序规则冲突

`t_notification` / `t_agent_data_audit` 建表时写了 `utf8mb4_general_ci`，而库内业务表主流是
`utf8mb4_0900_ai_ci`——提醒扫描的 `n.biz_id = t.id` 直接炸
`Illegal mix of collations (utf8mb4_general_ci,IMPLICIT) and (utf8mb4_0900_ai_ci,IMPLICIT)`。
修法是 `ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`
统一到业务主流（比在 SQL 里逐处 COLLATE 补丁干净），三个 schema 文件同步更正。
**新建表必须先看库内已有表的排序规则**，不要想当然写默认值。

实测（2026-11-06）：手动触发首轮返回 21（探针 2 条 + 全库其他到期任务各归其主），
探针收件箱「今天截止」「已逾期 5 天」两条文案正确；二次触发返回 0（去重生效）；
任务置 done 后再触发只重提逾期任务（停发生效）；审计快照成功与拦截各 1 条、
SQL / 行数 / 拦截原因齐全；用量页审计区块渲染正常。


## 约定说明

- **主键统一为字符串**：`varchar(64)`，由应用层生成（`UUID.randomUUID()` 去横线），不用数据库自增——写入前就能拿到 id，数据合并 / 迁移时也不会撞键。人 ID 与各类引用列（`create_user_id`、`update_user_id`、`role_id`、`menu_id`、`conversation_id`、`attachment_id`…）同为 `varchar(64)`，与主键宽度对齐，避免 join 时隐式转换。
  历史遗留的 `bigint` 主键由 `db/migrate_id_to_varchar32.sql` 用 `LPAD(id, 32, '0')` 转成 32 位定长字符串（`1` → `00000000000000000000000000000001`）。因此库里会看到「32 位补零历史值」与「32 位 UUID」两种形态并存：宽度一致、补零后字典序等于原数值序，排序与索引都不受影响。
  例外：`t_file_upload_log.business_id` 是业务对象 id（可能指向非 `t_` 表），保持 `bigint`，不参与这套约定。
- **审计只读**：`t_operation_log` 只增不改、没有删除接口，唯一入口是 `GET /api/logs`；历史归档交给运维脚本。
- 数据层只做持久化，不含业务判断；服务层承载业务规则与 `@Transactional` 事务边界。
- 写操作使用 `@Transactional(rollbackFor = Exception.class)`，查询使用 `readOnly = true`。
- 分页基于 `LIMIT/OFFSET` + 计数查询，条件为零结果时跳过列表查询，避免无效 SQL。
- 异常统一由 `GlobalExceptionHandler` 转换为 `Result`，不向前端暴露堆栈。
- 除白名单（`/api/auth/login`、`/api/auth/login-hint`、`/api/auth/captcha`）外，`/api/**` 请求必须带 `Authorization: Bearer <token>`。未登录由 `AuthInterceptor` 拦截，**同时**以 HTTP 状态码 401 与业务信封 `code: 401` 表示——两者并存是有意为之，便于前端任一命中即可判定（前端判定细则见 `../vue/docs/auth-frontend-spec.md`）。

## 排查记录：WebSocket 显示"未连接"且消息收不到

2026-10-22 修复。现象是：账号已登录，聊天页却一直显示"未连接"，对方发来的消息不出现
（表现为"发送有延迟"）。根因有两个，彼此独立，凑在一起才让现象难以反推。

### 一、服务端：推送报文序列化静默失败

日志证据：

```
WARN ChatServiceImpl - 构造未读推送失败 userId=... 原因=Java 8 date/time type
     `java.time.LocalDateTime` not supported by default: add Module "jackson-datatype-jsr310"
     (through reference chain: ...UnreadSummaryVO["lastTime"])
```

`buildUnreadPushPayload` 捕获异常后返回 `null`，`pushUnread` 见 null 直接 return——
于是连接、心跳、HTTP 接口全部正常，唯独一帧推送都发不出去。

根子在于项目没有把 `ObjectMapper` 暴露成 Spring bean，各处只能 `new ObjectMapper()`。
**裸实例不注册任何模块**，遇到 `LocalDateTime` 必然抛异常。

修法：统一使用 `common/JsonSupport.MAPPER`，它注册了 `JavaTimeModule` 并关闭
`WRITE_DATES_AS_TIMESTAMPS`（后者保证时间与 HTTP 接口一样输出 ISO-8601 字符串，
否则同一字段在接口里是 `"2026-10-22T15:24:08"`、在推送里却是 `[2026,10,22,...]`）。

> 教训：`catch` 住异常后只记一条 warn 就返回 null，会让故障完全隐形。
> 推送这类"尽力而为"的逻辑可以降级，但**必须让人看得见它降级了**。

### 二、前端：连接状态从未被更新

`stores/chat.ts` 里的写法是：

```ts
connectChat(handlePush)
online.value = isChatConnected()   // 此刻 socket 还在 CONNECTING，必然 false
```

而握手成功后没有任何代码再改这个值，所以**即使链路完全正常，页面也永远显示"未连接"**。
这一条掩盖了服务端的问题——看到"未连接"很容易误判成连接没建立，实际连接是好的。

修法：`utils/websocket.ts` 增加 `setChatStateListener`，由 `onopen` / `onclose` 主动通知，
状态与真实链路一致。

### 三、连带修复

| 问题 | 处理 |
|---|---|
| 收到消息时当前会话不刷新 | 推送新增 `pushTick` 计数器，页面监听它而非 `unreadTotal`（后者会因标记已读而不变，导致漏刷新） |
| 正在看的会话仍挂未读 | 推送后若未读项包含当前会话，立即 `markCurrentRead` |
| Vite 只代理 `/api`，未代理 `/ws` | 补上 `/ws`（`ws: true`），使 `VITE_API_BASE_URL` 缺失时 WS 也能同源到达后端 |

### 实测

```
握手：HTTP 101
连接后首帧（未读概览）：立即到达
对方发消息 → 本端收到推送：146ms（直连 8080）/ 168ms（经 Vite 代理）
发送接口 HTTP 往返：171 ~ 185ms
```

## 时间规划模块（t_gantt_plan + t_gantt_task + t_gantt_dependency）

**菜单**：顶级「时间规划」（`m24`，路径 `/gantt`，`permission = gantt`），默认授权超级管理员与普通用户。

**数据权限**：超级管理员可见并操作全部计划；其他用户只能看到 / 修改**自己创建**的计划及其任务、依赖。详见下文「数据权限」小节。

### 数据表

| 表 | 一行 = | 关键字段 |
|---|---|---|
| `t_gantt_plan` | 一张甘特图（一次排期） | `plan_name`、`start_date` / `end_date`（仅作初始视图范围）、`status` |
| `t_gantt_task` | 一个任务 / 里程碑 / 分组 | `plan_id`、`parent_id`、`start_date` / `end_date`、`progress`、`status`、`assignee`、`priority`、`is_milestone`、`sort_num` |
| `t_gantt_dependency` | 一条前后置关系 | `plan_id`、`from_task_id` → `to_task_id`、`dep_type`（FS/SS/FF/SF）、`lag_days`、`del_flag`；有效记录由生成列 `alive_from_to` 保证唯一 |

### 三个设计选择与理由

1. **里程碑不单独建表**，用 `is_milestone = 1` + 起止同一天表达。
   里程碑本质是零工期任务，允许被依赖、被排序、被统计；单独建表会让依赖关系出现两套外键，甘特图也要渲染两种实体。
   服务层强制「里程碑起止同日」，即使前端传了区间也按开始日收敛，避免画出有宽度的菱形。

2. **依赖单独建表**，而不是在任务行里存 `predecessor` 字段。
   一个任务可以有多个前置（多对多），塞进一行只能存 CSV：加不了唯一约束，删任务时也没法精确清理关联。

3. **父任务的起止与进度是派生的**：有子任务时，起止取子任务的最小 / 最大日期，进度取子任务平均值，状态按「全部完成 → done / 有推进 → doing / 其余 → todo」汇总。
   库里仍保留用户填过的值，但读取一律以子任务为准——否则改完子任务，父任务的条子还停在老位置。
   只允许一层父子：多层会让汇总变成递归，移动端窄屏也无法阅读缩进。

4. **依赖也是软删**。删任务、删计划都会连带处理它参与过的依赖；如果依赖是 `DELETE` 硬删，误删一个任务就等于丢掉整条链路的记录，事后无从追溯。
   难点在唯一键：原来建在 `(from_task_id, to_task_id)` 上，软删后同一对任务重新建依赖会撞键，删一次就再也建不回来。
   解法是加一个 `STORED` 生成列 `alive_from_to`——有效记录取 `from:to`，已删除记录为 `NULL`，唯一索引建在它上面：
   MySQL 的唯一索引不约束 `NULL`，于是墓碑可以有多条、有效记录仍然唯一，「删了再建」自然成立。

### 交互设计（对标 MS Project / Asana / TeamGantt）

| 交互 | 做法 | 为什么 |
|---|---|---|
| 拖任务条改期 | 按住条子左右拖，整条平移；位移按天取整后回写起止日 | 改日期是最高频操作，弹窗填两个日期等于把 2 秒的事做成 20 秒 |
| 拖右缘调时长 | 条子右缘 8px 把手（深灰双竖纹，hover 显形），横向拉伸；结束日不小于开始日 | 与拖整条区分开，避免「想延长却把任务挪走了」 |
| 把手的层叠 | 把手 `z-index: 2`，必须压过任务名文字层（`z-index: 1`） | 文字层盖住把手时，事件被它截走，把手看起来「点不到」——用户拖右缘只会整条平移，反馈成「进度条拉不长也缩不短」 |
| 三层嵌套 | 顶层分组 → 二级分组 → 任务，上限 3 层（`MAX_TASK_DEPTH`）；父任务任意层级未达上限即可 | 两层不够用（阶段 → 模块 → 任务）；再深则递归汇总与缩进渲染失控，窄屏没法读 |
| 递归汇总 | 分组起止 = 全部后代 min/max，进度 = 后代平均，状态由后代状态归纳；二/三级分组基于子 VO 汇总值再汇总 | 用实体自身字段汇总会让二级分组带上过期日期，顶层也就跟着算错 |
| 层级约束 | 第四层创建 400；父链上出现自己 400（成环）；有子任务的节点不能改挂到别人下面 | 环会让递归死循环；整棵子树搬家牵扯太多约束，改父只开放给叶子 |
| 前置任务下拉 | 按树形全展开排列，子任务带 `└` 前缀、缩进、弱化字重，日期靠右灰字 | 平铺列表里各级分组和子任务混在一起，分不清谁挂在谁下面，很容易选错前置 |
| 删除的两级入口 | 撤销横条（纯内存态，只针对刚发生的删除）+ 回收站弹窗（服务端查库里的软删记录，随时恢复） | 一切业务数据以数据库为准，前端不再自建第二数据源：横条管「马上反悔」，回收站管「事后找回」 |
| 回收站双页签 | 「任务」挂在当前计划下，「计划」是全局的；按钮不挂 `activePlan` 条件常驻 | 删掉计划后没有选中计划，任务页签进不去——那恰恰是最需要恢复入口的时刻 |
| 今日线 | 1px 竖线淡化到 0.45，表头日期行底部钉一枚红底「今天」小标签 | 纯红线容易和「告警」混淆，也容易被第一行任务条盖住；标签吸顶常驻，缩放切换后一眼能找到今天 |
| 任务表格无背景 | 表头 / 侧栏 / 行 hover 全部白底，靠边框与字重分层 | 灰色块让表格看起来「有底纹」，和甘特图画布的留白不协调；行的反馈交给箭头高亮 |
| 点击 vs 拖拽 | 位移未满一天时仍是点击（开编辑窗）；拖动过则吞掉随后的 click | `mouseup` 后浏览器还会补发 click，不复位标志就会「拖完日期变了、编辑窗也弹出来」 |
| 分组折叠 | 分组行前的三角按钮，折叠是纯视图状态 | 任务一多，滚动找今天的成本远高于折叠；折叠分组的子任务仍参与 `used` 集合，否则会落到「孤儿兜底区」重复渲染 |
| 悬浮提示 | 自绘 tooltip（名称 / 状态 / 日期 / 天数 / 负责人 / 进度） | 原生 `title` 有约 1 秒延迟、样式不可控，而且**拖拽时会跟着弹**，干扰操作 |
| 依赖高亮 | 悬停任务时相关箭头变橙加粗、其余淡出；相连任务描边提亮 | 依赖一多，箭头就成了一团灰线，看不出某条任务卡在谁身上 |
| 定位今日 | 首次拿到任务、切换缩放时把今日线滚到视口中部 | 计划跨度常有几个月，打开就停在 9 月、还要手动滚到 11 月，等于每次都在找今天 |

刻意没做的：拖拽建依赖（从条子边缘拖出箭头）、关键路径、基线对比。它们要么需要额外的交互层级（拖拽建依赖与「拖条改期」在同一个热区上冲突，得靠按住某个修饰键区分），要么需要新增数据模型（基线要另存一版快照）。这三项都不是「体验不好」的主因，先把高频操作做顺。

### 数据权限

规则一句话：**超管看全部，其他人只看自己创建的**。判定与执行分三处：

| 层 | 位置 | 做法 |
|---|---|---|
| 判定 | `service/DataScopeService.java` | 查 `t_user_role` 是否含 `ROLE_SUPER_ADMIN`；`dataScopeOwnerId()` 返回 `null`（不限制）或当前用户 id |
| 列表过滤 | `GanttMapper.selectPlans(keyword, ownerId)` | `<if test="ownerId != null"> AND p.create_by = #{ownerId} </if>`，超管传 null 时条件不拼 |
| 单条校验 | `requireAccessiblePlan / requireAccessibleTask` | 取出数据后比对 `create_by`，不一致抛 403 |

四个刻意的决定：

1. **归属以「计划」为单位，任务跟随其计划**。不按任务的 `create_by` 判断——超管可以在别人的计划里代建任务，
   若按任务创建人算，这条任务对计划主人不可见、对超管可见，同一条数据两种视角，权限就说不清了。
2. **越权返回 403 而不是伪装 404**。计划不是敏感资源，直接说「这不是你的」比让用户以为数据丢了更省沟通。
3. **写操作与读操作同一套校验**。只做读隔离会留下「看不见但知道 id 就能改」的漏洞——`updateTask / deleteTask /
   createDependency / deleteDependency` 全部走同一组 `requireAccessible*`。
4. **归属标记由服务端算**（`GanttPlanVO.owned`）。前端不拿本地用户 id 去比对：刷新后用户信息可能还没加载、
   mock 环境下压根取不到，那样会把「自己的计划」误标成别人的。

角色判定只查一次、作为参数往下传，不在每条 SQL 里 `JOIN t_user_role`——角色在一次请求内不会变，
逐条连接纯属浪费；代价是每个入口方法都必须显式取一次 `dataScopeOwnerId()`，漏传就会看到别人的数据。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/gantt/plans` | 计划列表，带任务数 / 里程碑数 / 完成数 / 平均进度；**按数据范围过滤** |
| POST | `/api/gantt/plans` | 新建计划，返回带 id 的 VO |
| PUT | `/api/gantt/plans/{id}` | 修改。**提交即完整覆盖**：可清空字段（描述）传 `null` 表示清空 |
| DELETE | `/api/gantt/plans/{id}` | 级联软删任务、清理依赖 |
| GET | `/api/gantt/plans/{id}` | **甘特图详情**：计划 + 任务 + 依赖一次返回 |
| POST | `/api/gantt/plans/{planId}/tasks` | 新建任务，返回新任务 id（前端接着建依赖要用） |
| PUT | `/api/gantt/tasks/{id}` | 修改任务。**提交即完整覆盖**：父任务 / 说明 / 负责人 / 颜色传 `null` 表示清空 |
| DELETE | `/api/gantt/tasks/{id}` | 删除任务；`?cascade=true` 时连同子任务一起删（前端二次确认后才传） |
| POST | `/api/gantt/dependencies` | 新增依赖 |
| DELETE | `/api/gantt/dependencies/{id}` | 删除依赖（软删，记录保留） |
| POST | `/api/gantt/plans/{id}/restore` | 恢复软删计划：连同其下全部任务与依赖 |
| POST | `/api/gantt/tasks/{id}/restore` | 恢复软删任务（按家族：父 / 兄弟 / 子任务一起回来），附带可恢复的依赖 |
| POST | `/api/gantt/dependencies/{id}/restore` | 恢复单条软删依赖；两端任务与计划都必须为有效状态 |
| GET | `/api/gantt/plans/{id}/deleted-tasks` | 回收站：计划下已删任务列表（按删除时间倒序，带已删后代数） |
| GET | `/api/gantt/deleted-plans` | 计划级回收站：可见范围内已删计划（带已删任务数），删计划刷新后仍可恢复 |

**校验规则**（全部在服务层，注解表达不了跨字段 / 跨表约束）：

| 规则 | 违反时 |
|---|---|
| 结束日不得早于开始日 | 400 |
| 里程碑起止必须同一天 | 自动收敛，不报错 |
| 任务不能依赖自己 | 400 |
| 两个任务必须属于同一计划 | 400 |
| 同一对任务只能有一条依赖 | 409 |
| 新增依赖不得形成环（迭代式 DFS 从 to 走回 from） | 400「会形成循环依赖」 |
| 父任务必须存在、同计划、且本身是顶层 | 400 |
| 有子任务的任务默认不能删除 / 不能再认父 | 409（删除可传 `cascade=true` 放行） |

> 为什么用迭代 DFS 而不是递归：任务链长度取决于用户数据，递归深度不可控，栈溢出属于不可恢复的故障。

**更新语义：PUT = 提交即覆盖**。编辑弹窗每次都提交完整表单，因此「可清空」的字段
（任务的父任务 / 说明 / 负责人 / 颜色，计划的描述）传 `null` 就写 `NULL`。
早期这里写的是「null = 不更新」，结果是「清空负责人」「把子任务从分组里拿出来」
静默失败：接口 200、界面提示「已保存」、回读还是旧值——这类不一致最难排查，
因为它既没有报错也没有日志。服务端维护的 `sort_num` 仍保持「null 不更新」。

**分组任务的派生字段不在弹窗里改**：时间区间 / 状态 / 进度由子任务汇总，
弹窗内这三项直接禁用并标注「由子任务汇总」，避免用户改完再困惑为什么没生效。

### 前端实现

| 关注点 | 做法 |
|---|---|
| 布局 | **单滚动容器 + 左侧列 `position: sticky; left: 0`**。不用「左右两个容器 + scroll 事件互相同步」——那要处理惯性滚动、滚动条高度差、程序设 `scrollLeft` 的回环，是这类组件最常见的抖动来源 |
| 时间轴 | 日 / 周 / 月三档缩放（30 / 11 / 4.4 px 每天），范围 = 计划区间 ∪ 所有任务区间，前后各留 2 天；周视图对齐到周一、月视图对齐到月初 |
| 任务条 | 普通任务 = 底色 + 进度填充；分组任务 = 矮条 + 两端折角；里程碑 = 旋转 45° 的方块钉在开始日 |
| 依赖箭头 | SVG 覆盖在时间轴区域，`pointer-events: none` 不挡点击。按依赖类型挂边：FS 右端→左端，SS 左端→左端，FF 右端→右端，SF 左端→右端；后置任务在前置左侧时走 S 形绕行 |
| 今日标记 | 红色竖线 + 表头当日红底 |
| 移动端 | **不缩放桌面图**：390px 下月视图只有 4px/天，条子会叠成色带。改为卡片列表，条子按「占整体跨度的比例」画，仍能看出先后与并行；前置任务以文字列出替代箭头 |
| 缓存 | 无：缩放级别是纯视图偏好不落库不落 localStorage，每次进入默认周视图；撤销横条为内存态，恢复一律走数据库软删记录（回收站） |

文件：

| 层 | 路径 |
|---|---|
| 实体 | `repository/entity/GanttPlan.java`、`GanttTask.java`、`GanttDependency.java` |
| Mapper | `repository/mapper/GanttMapper.java` + `resources/mapper/GanttMapper.xml` |
| Service | `service/GanttService.java`、`service/impl/GanttServiceImpl.java`；数据范围 `service/DataScopeService.java` |
| Controller | `controller/GanttController.java` |
| DTO / VO | `model/dto/GanttPlanRequest.java`、`GanttTaskRequest.java`、`GanttDependencyRequest.java`；`model/vo/GanttPlanVO.java`、`GanttTaskVO.java`、`GanttDependencyVO.java`、`GanttPlanDetailVO.java` |
| 建表 | `resources/db/schema-gantt.sql` |
| 前端 | `views/gantt/GanttView.vue` + `components/GanttChart.vue`、`GanttMobileList.vue`、`GanttTaskDialog.vue`、`GanttPlanDialog.vue`、`utils/date.ts`；接口 `api/gantt.ts`；路由 `/gantt` |

### 实测（2026-11-05）

| 检查 | 结果 |
|---|---|
| 接口全流程 22 项（建计划 / 任务 / 里程碑 / 依赖、重复依赖、成环、自依赖、跨层级依赖、父子汇总、删除保护、日期校验、统计、级联删除） | 全部通过 |
| 数据权限 24 项（超管看全部；普通用户列表隔离；猜 id 读 / 改 / 删他人计划与任务、往他人计划建任务、用他人 taskId 建依赖、删他人依赖，均 403） | 全部通过 |
| 归属标记 8 项（`owned` 服务端判定；超管看他人计划标出创建人，看自己的不标） | 全部通过 |
| 编辑保存 16 项（改名 / 日期 / 状态 / 进度 / 优先级、清空负责人 / 说明 / 颜色、取消与重挂父任务、计划的说明与状态） | 全部通过 |
| 页面编辑 | 改名 + 清空负责人保存后重开仍生效、进度拖到 80% 落库；分组任务的日期 / 状态 / 进度禁用并标「由子任务汇总」；取消分组后子任务升为顶层行 |
| 删除任务 10 项（普通任务、带依赖任务、分组默认 409、`cascade=true` 连子任务一起删、子任务依赖一并清理、删不存在的任务 404） | 全部通过 |
| 页面删除 | 编辑弹窗左下「删除任务」→ 二次确认 → 行消失且统计归零；分组任务确认文案报出「一并删除 2 个子任务」，确认后父与子全部消失；新建任务弹窗无删除按钮 |
| 依赖软删 13 项（删依赖后记录仍在且 `del_flag=1`、详情不再返回、同一对任务可删了再建、重复的有效依赖仍 409、重复删 404、删任务 / 删计划连带软删且一条未少） | 全部通过 |
| 页面软删 | 删除中间任务后两条依赖箭头同时消失、无悬空线；重新建依赖能正常画出 |
| 恢复 14 项（单任务恢复含依赖、删分组后恢复任意成员带回全家、依赖恢复冲突跳过、删计划后恢复计划 + 任务 + 依赖、计划已删时恢复任务 409、重复恢复 404） | 全部通过 |
| 三层 15 项（三层创建、第四层 400、环检测 400、二级与顶层递归汇总、叶子延期传导、状态归纳、级联删除 3 条、回收站后代计数、恢复分组带回孙级、计划回收站列出/计数/恢复/清空） | 全部通过 |
| 页面 6 项（三层缩进 0/14/28、二级分组折叠整棵子树、今日线 0.45 + 「今天」标签、回收站按钮常驻、计划页签空态） | 全部通过 |
| 页面交互 9 项（日视图自动滚到今日、悬浮提示字段齐全、悬停高亮 2 条箭头淡出 1 条、拖条右移 3 天落库且不弹窗、拉右缘延长、点击仍开弹窗、折叠少 2 行、删除后撤销横条、撤销后任务回来） | 全部通过 |
| 页面删计划 → 撤销 | 横条提示计划与任务数，撤销后计划回到列表并自动选中、5 行任务齐全 |
| 回收站 7 项（列出已删任务、分组标注已删子任务数、删除时间、恢复分组带回 3 条、恢复后列表清空、依赖随家族恢复） | 全部通过 |
| 页面：表格无灰底 / 撤销横条内存态（刷新后消失，恢复走回收站）/ 回收站弹窗列表与恢复 / 删除按钮浅红胶囊（#fef2f2 + 8px 圆角 + 图标） | 全部通过 |
| 父任务汇总 | 两个子任务进度 100 / 50 → 父任务 75；区间取子任务最小~最大 |
| 里程碑 | 传入 `11-20 ~ 11-25` 落库为 `11-20 ~ 11-20`，`durationDays = 1` |
| 页面 | 12 行任务、11 个任务条 + 1 个菱形、8 条依赖箭头全部渲染；横向滚动时左侧列不动 |
| 缩放 | 日 / 周 / 月切换正常（滚动宽度 1796 / 657 / 1164） |
| 编辑 | 点击任务条打开弹窗，名称回填正确；新建任务保存后立即出现在图上并刷新统计 |
| 移动端 390px | 自动切卡片视图，无横向溢出 |

## 安全加固记录（2026-11-08）

本轮按「可复现再利用」的标准做了一次审计，下面只列**已确认并修复**的问题与**行为变化**；
每条都在真实 MySQL / 启动实例上复现过（SQL 绕过用 `EXPLAIN` 与 SELECT 验证，HTTP 项用 18080 实例验证）。

| # | 问题 | 修复 |
|---|---|---|
| 1 | `ReadSqlGuard` 的 CTE 同名遮蔽：`WITH t_user AS (SELECT * FROM t_user) SELECT * FROM t_user` 让凭证表整表可读 | CTE 名与真实表同名直接拒绝；执行前再用 `EXPLAIN FORMAT=JSON` 复核 MySQL 实际访问的表（`AgentDataServiceImpl#explainAccessedTables`），文本正则 + 解析结果双保险 |
| 2 | 括号表引用 `FROM t_knowledge, (t_user)` 对提取器不可见 | `FROM (表名)` 直接拒绝；派生表 `FROM (SELECT …)` 仍允许（内层 FROM 会被继续扫描） |
| 3 | 末尾 LIMIT 剥离作用在未屏蔽原文：`WHERE title = 'limit 3'` 被改写成 `''`，子查询 `LIMIT 1` 被删 | 只匹配**末尾**的顶层 LIMIT；字符串与子查询里的 LIMIT 原样保留 |
| 4 | `PUT /api/menu/roles/{id}` 不校验角色「现有等级」：1 级管理员可把超管角色改成 10 级，再分配给自己 | `updateRole` 先比较现有等级，高于操作者一律 403；`grantMenusToRole` 同时要求「只能授出自己已拥有的菜单」（顶级管理员除外） |
| 5 | 登录失败计数用原始用户名，而 `t_user.username` 是大小写不敏感排序规则：换大小写即可重置锁定/验证码桶 | `LoginAttemptService#scope` 统一 `trim + toLowerCase` |
| 6 | 防枚举的 dummy BCrypt 哈希只有 59 字符，格式预校验直接返回，账号不存在时快约 80ms | 换成合法的 60 字符哈希（`AuthServiceImplTest` 锁定格式与长度） |
| 7 | local 档下随包分发的公开密钥被继续使用；Redis 不可用时验签即放行，等于可离线伪造任意 Token | local/dev/test 检测到弱默认值时**替换为进程内随机 32 字节密钥**；非本地档仍直接拒绝启动。多实例部署必须显式注入 `AUTH_TOKEN_SECRET` |
| 8 | 异步请求（SSE / `Callable` / `StreamingResponseBody`）不触发 `afterCompletion`，`AuthContext` 残留在容器线程上 | `AuthInterceptor` 改为 `AsyncHandlerInterceptor`，`preHandle` 与 `afterConcurrentHandlingStarted` 都清理身份 |
| 9 | 改密 / 重置密码 / 禁用 / 删号只吊销 HTTP 会话，已建立的 WebSocket 仍持续收到含消息正文的未读推送 | `ChatSessionRegistry.closeUser` 在上述路径同步关闭该用户全部连接；单用户连接数上限 8 |
| 10 | 聊天历史分页无深分页上限（其余模块都有 100k 防线） | `ChatServiceImpl` 增加 `MAX_OFFSET = 100_000` |
| 11 | `deleteRole` / `deleteMenu` 真实调用 500：补丁对象只带 `id + delFlag`，而 `updateRole`/`updateMenu` 的 `<set>` 没有 `del_flag`，拼出 `UPDATE t_role WHERE …` | XML 补 `del_flag` 条件写；回读列表/树确认已消失（此前单测 mock 掉了 Mapper，所以没暴露） |
| 12 | 附件目录「互不包含」只校验了 `chat ⊂ uploads` 一个方向 | 双向 `startsWith` 校验 |
| 13 | 附件异常原文回显（`FileSystemException` 含服务器绝对路径）；ZIP 条目名依赖上传侧净化；扩展名未限制字符集 | 对外固定文案、细节只进日志；打包时再次净化条目名；扩展名收紧为 `[a-z0-9]{1,16}` |
| 14 | `agent_config` 脱敏按 label 黑名单，改个名字/新增项即明文泄漏 | 改为白名单：普通用户只返回 `default_model` / `system_prompt` |
| 15 | 甘特依赖恢复绕过成环校验（删依赖→建新边→恢复）；计划列表 LIKE 未转义 | 三条恢复路径（plan/task/dependency）恢复前重新 `requireNoCycle`；`plan_name LIKE … ESCAPE '/'` 并接 `LikeEscape` |
| 16 | 问数敏感表与库表浏览口径不一致（超管可经问数读 AI 私聊）；视图会被当合法表进入白名单 | `t_agent_message` / `t_agent_conversation` 移入凭证表；schema 快照只取 `BASE TABLE` |
| 17 | 智能体流式线程池全局 4 线程，单账号可占满导致所有用户 429 | 单账号并发流上限 2，超限直接返回提示 |

**运维需要知道的行为变化**

- 未显式配置 `AUTH_TOKEN_SECRET` 时，每次启动都会生成新的随机密钥：单实例无感，**多实例/重启后旧 Token 会全部失效**，生产必须显式注入。
- AI 读库多了一次 `EXPLAIN` 校验（只读、不执行），SQL 里出现与真实表同名的 CTE / 括号表名会被拒绝。
- 菜单/角色删除现在真的会写 `del_flag='1'`（以前是 500 或静默删不掉）。

**审计确认存在、但本轮未改的问题（需要产品/运维决策）**

- **附件模块没有行级归属**：`attachments` 菜单被授予非超管后，该角色可看到并删除所有人上传的文件。这是「共享文件库」的产品模型，不是代码缺陷；若要改成个人文件，需要同步改列表、下载、回收站与搜索。
- 同级管理员可互相重置密码/禁用（`requireNotAboveTarget` 用 `>` 而非 `>=`），是否允许取决于管理组织结构。
- 验证码在 Redis 故障时 fail-open（`CaptchaService.verify` 返回 true）、改密后的会话吊销失败只记 warn：两处都是「安全 vs 可用性」的显式取舍，当前选择可用性优先。
- 反向代理部署未配 `server.forward-headers-strategy` 时，登录锁定的 IP 维度会退化为代理 IP；问数仍复用主数据源（建议单独建只读账号）。
- WebSocket 推送仍在请求线程同步写、无发送超时，慢客户端可拖住发送方线程（有界，但未异步化）。
