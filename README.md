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
| 5 | `db/migrate_knowledge_index.sql` | 知识库全文索引 `ft_knowledge_search` | 可选（关闭全文检索则不需要） |
| 6 | `db/migrate_id_to_varchar32.sql` | 老库升级：`bigint` 主键转字符串并 `LPAD` 补零 | 仅从旧版本升级时 |

```bash
for f in schema.sql schema-im-menu.sql init-im-menu.sql schema-operation-log.sql; do
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
**共 11 个视图层、84 个端点**，明细见各模块章节：

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

> 「放行」指不参与菜单授权：`AuthInterceptor` 的匿名白名单只有 3 个（登录 / 登录前置 / 验证码），
> 其余放行都是 `MenuAccessInterceptor` 里的路径规则，见「菜单与授权模块 › 双层防线」。

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

再执行 `db/schema-operation-log.sql` 会**追加**「系统日志」菜单 `m21`（`permission=logs`，挂在系统管理下），
并只授给超级管理员——普通用户需要在角色管理里手工分配，否则访问 `/api/logs` 会得到 403。

验证效果（实测）：超级管理员可见 21 项（含追加的系统日志），普通用户仅 4 项；普通用户访问 `/api/knowledge` 放行、访问 `/api/databases` 或 `/api/menu/tree` 返回 403。

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
