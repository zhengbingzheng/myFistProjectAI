# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Spring Boot 4.1.1 + MyBatis 4.1.0 + MySQL 8 的分层后端，业务模块包括：认证、用户、菜单与角色授权、知识库、导航、附件、数据字典、数据库元信息浏览、即时通信（WebSocket）。

`README.md`（约 1200 行）是主要设计文档：每个模块的接口表、字段映射、方案取舍和踩坑记录都在里面。改动某个模块前先读对应章节，不建议只凭代码推断设计意图。

## 构建与运行

本项目无 Maven Wrapper，用系统 `mvn`（可用 JDK 26，`pom.xml` 中 `release=19` 限制字节码目标）。

```bash
mvn -U clean package -DskipTests          # 打包
mvn spring-boot:run                       # 直接运行
java -jar target/erupt-example-0.0.1-SNAPSHOT.jar
mvn test                                  # 全部测试
mvn test -Dtest=TokenServiceTest          # 单个测试类
mvn test -Dtest=NavigationServiceImplTest#page_xxx   # 单个方法
```

**测试依赖真实中间件**：`src/test/resources` 不存在，`@SpringBootTest` 类（`EruptExampleApplicationTests`、`LocalProfileImportTest`）会连本机 MySQL（库 `erupt-example`）；`TokenServiceTest` 等 Mockito 单测不需要。Redis 未起时应用仍能启动（有降级），但涉及 Redis 的路径行为会变。

数据库初始化（按顺序）：

```bash
mysql -uroot -p < src/main/resources/db/schema.sql        # 核心表 + admin 初始账号
mysql -uroot -p < src/main/resources/db/schema-im-menu.sql   # 聊天 + 菜单/角色表
mysql -uroot -p < src/main/resources/db/init-im-menu.sql     # 角色与菜单初始数据
```

接口文档：`/doc.html`（Knife4j）、`/swagger-ui.html`、`/v3/api-docs`。

## 配置与凭据

- `spring.profiles.default=local`，并通过 `spring.config.import: optional:file:./config/application-local.yml` 从**工程根目录**加载本机凭据。该文件被 gitignore，不进 jar。不要把凭据挪回 `src/main/resources`——`LocalProfileImportTest` 就是守这一点的。
- 所有连接串/密钥都可用环境变量覆盖（`DB_*`、`REDIS_*`、`AUTH_TOKEN_SECRET` 等），见 `application.yml` 注释。
- `AuthSecretGuard`：非 local/dev/test 档下若 `app.auth.token-secret` 仍是随代码分发的默认值或短于 32 字符，**直接终止启动**。改密钥逻辑时别绕过它。

## 分层与横切约定

依赖方向严格单向：`controller → service → repository`。

- **controller**：只做绑定、`@Valid` 校验、`ResponseEntity` 包装。新建返回 201。
- **service / impl**：业务规则与 `@Transactional` 边界（写用 `rollbackFor = Exception.class`，读用 `readOnly = true`）。返回 `Result<T>`，不抛裸异常除 `BusinessException`。
- **repository/entity**：与库表列名一一对应（`knowledgeName` 等）；**对外 DTO/VO 用简洁名**（`name`/`content`），转换由 MapStruct 完成。
- **converter**：`@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)`，新增 VO 字段忘记映射会编译失败，这是有意的。Service 里用 `Mappers.getMapper(XxxConverter.class)` 取实例。
- **common**：`Result<T>`(code/message/data/timestamp) + `ResultCode` + `PageResult<T>` + `BusinessException` + `GlobalExceptionHandler`。`GlobalExceptionHandler` 的每个 handler 都在修正"语义错误被当成 500"的问题，新增异常类型时按同样思路映射状态码。

关键横切：

- **鉴权**：没有 Spring Security 过滤器链（只引入了 `spring-security-crypto` 做 BCrypt）。`AuthInterceptor` 拦 `/api/**`，凭证为 `Authorization: Bearer <token>`；401 同时体现在 HTTP 状态码和响应体 `code`。匿名路径在 `AuthProperties.permitPaths`，`isPermit` 只放行精确路径或"前缀 + `/`"，新增匿名端点必须显式加进去。
- **身份**：`AuthContext` 是 ThreadLocal，`AuthInterceptor.afterCompletion` 负责清理；任何绕过拦截器写身份的地方都要配套 `clear()`。
- **Token**：`TokenService` 自实现 HMAC-SHA256 签名 + Redis 会话（可吊销）。Redis 不可用时降级为"只验签名与有效期"。
- **缓存**：`RedisCacheService` 是唯一的 Redis 访问入口，设计原则是**缓存失效绝不能影响业务**——开关短路、异常降级回源、连续失败熔断 30 秒。key 一律走 `CacheKeys`（动态片段做 SHA-256 摘要）。注意 `NavigationServiceImpl` 目前**不用缓存**（注释解释了原因），`CacheKeys`/`app.cache.redis.navigation.*` 是为其保留的。
- **软删除 + 唯一约束**：用 MySQL 生成列（`t_user.username_active`、`t_dict.dict_type_active`）让唯一索引只作用于未删除行；软删除后同编码可重建。新增类似表沿用此模式。
- **主键**：字符串，32 位 UUID 去横线，应用层生成；`db/migrate_id_to_varchar32.sql` 是历史迁移脚本。
- **LIKE 查询**：用户输入必须经 `LikeEscape.escape()`，SQL 里配 `ESCAPE '/'`。
- **分页**：`LIMIT/OFFSET` + 计数；偏移量超 `MAX_OFFSET`（10 万）直接拒绝；计数为 0 时跳过列表查询。

## Jackson 2 与 3 并存（最容易踩的坑）

Spring Boot 4.1.1 管理 Jackson 3，包名是 `tools.jackson.*`，且 API 有破坏性变化（`ObjectMapper` 不可变、读写方法移到 `ObjectReader`/`ObjectWriter`、`WRITE_DATES_AS_TIMESTAMPS` 已移除）。但 classpath 上仍有 Jackson 2（`com.fasterxml.*`）被其他依赖引入。

- 缓存序列化用 `tools.jackson` + `RedisCacheService` 注入的 `@Qualifier("cacheObjectMapper")`；缓存契约刻意与 Web 层解耦，且关闭 `FAIL_ON_UNKNOWN_PROPERTIES` 以容忍旧缓存结构。
- 手写 JSON（WebSocket 推送等）统一用 `common/JsonSupport.MAPPER`（Jackson 2，注册 `JavaTimeModule`）。**不要 `new ObjectMapper()`**——裸实例遇到 `LocalDateTime` 会抛异常，而消息推送里的 catch 会把它变成"静默推不出去"，历史上真实发生过（见 README 排查记录）。
- 需要新增一个模块的 ObjectMapper 时，先确认它属于哪个 Jackson 大版本。

## WebSocket（即时通信）

- 原生 `TextWebSocketHandler`，未用 STOMP。端点 `/ws/chat`，来源白名单 `app.ws.allowed-origins`（不能是 `*`）。
- 握手即鉴权，凭证走 URL 参数：优先一次性 `ticket`（`TokenService` 的 `ws:ticket:`，60 秒 TTL），退回 `token`/`access_token` 兼容旧前端。浏览器 WS 不能加请求头，所以 token 会进访问日志——这是采用短 TTL 的原因。
- `ChatSessionRegistry` 维护 userId → 多连接的**内存** Map（多标签页/多设备都要收到）。多实例部署时替换为 Redis Pub/Sub 转发，上层不用改。
- 推送必须在 `TransactionSynchronization.afterCommit` 之后，否则对端会"收到提醒但拉不到消息"。

## 附件存储

两套完全独立的实现，**不要合并**：

- `AttachmentService`：`./uploads`，有回收站、批量打包、预览。
- `ChatAttachmentService`：`./chat-uploads`，无回收站。

两个目录刻意平级而非嵌套（备份/清理脚本以附件根目录为界），启动时会校验互不包含。落盘名为 `<主键>.<ext>`，原始文件名只作展示，以此阻断路径穿越。下载/打包全程流式。

## 代码风格

- 中文注释，且倾向于解释"为什么这么做"以及"不这么做会出什么事"，而不是复述代码。改动时保持这个风格。
- 构造器注入，字段 `private final`；Lombok 用于 `@Slf4j` / 实体 getter/setter。
- 单元测试用 JUnit 5 + Mockito + AssertJ，类上 `@ExtendWith(MockitoExtension.class)`，方法加 `@DisplayName`；`XxxServiceImplTest` 只 mock Mapper，不启动 Spring 上下文。
