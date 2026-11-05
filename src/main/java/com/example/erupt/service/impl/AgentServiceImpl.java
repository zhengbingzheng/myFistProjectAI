package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.JsonSupport;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.AgentChatRequest;
import com.example.erupt.model.vo.AgentAttachmentVO;
import com.example.erupt.model.vo.AgentChatVO;
import com.example.erupt.model.vo.AgentConversationVO;
import com.example.erupt.model.vo.AgentMessageVO;
import com.example.erupt.model.vo.DictItemVO;
import com.example.erupt.repository.entity.AgentConversation;
import com.example.erupt.repository.entity.AgentMessage;
import com.example.erupt.repository.entity.Attachment;
import com.example.erupt.repository.mapper.AgentMapper;
import com.example.erupt.service.AgentService;
import com.example.erupt.service.AttachmentResource;
import com.example.erupt.service.AttachmentService;
import com.example.erupt.service.DictService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体实现：GLM（OpenAI Chat 协议）多轮对话。
 *
 * <p>三个关键决策：</p>
 * <ul>
 *   <li><b>配置走字典不走 application.yml</b>——API Key / 端点要运营时改，
 *       每次调用实时读 {@code agent_config}，改完即生效；代价是每轮多一次字典查询（走索引，可忽略）。</li>
 *   <li><b>多轮上下文由后端组装</b>——从消息表取最近 20 条原样回传（API 无状态），
 *       前端不回传历史，防止伪造角色 / 绕过落库。</li>
 *   <li><b>上游错误翻译成人话再抛</b>——智谱的错误码（1000 认证失败、1261 超长、1301 审核、
 *       1302 限流…）对使用者没有意义，翻译到「该去哪里改配置 / 该怎么办」的粒度。
 *       注意上游 401 绝不能映射成本系统 401，否则前端的全局 401 处理会直接把人登出。</li>
 * </ul>
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    /** 字典类型编码：API Key / chat_url / default_model / system_prompt 都在这下面 */
    static final String DICT_TYPE = "agent_config";

    /** 上游默认端点（字典缺 chat_url 项时的兜底） */
    private static final String DEFAULT_CHAT_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /** 前端可用的模型白名单：新模型上线时同步改这里与前端选项 */
    private static final Set<String> ALLOWED_MODELS = Set.of("glm-5.3", "glm-5.3-flash");

    /** 每轮携带的历史上限：控制 token 体量，超出的旧轮次不再回传 */
    private static final int MAX_CONTEXT_MESSAGES = 20;

    private static final int MAX_CONVERSATIONS = 100;

    private static final int TITLE_MAX_LENGTH = 50;

    /** 单个文本附件读入上限：32KB，超出截断（再大就不该靠粘贴塞进上下文） */
    private static final int MAX_ATTACHMENT_BYTES = 32 * 1024;

    /** 一轮对话的附件正文总预算：96KB，防止一条消息把上下文吃满 */
    private static final long MAX_ATTACHMENT_TOTAL_BYTES = 96 * 1024;

    /** 视为「可读文本」的扩展名（浏览器 contentType 不可靠时的兜底判断） */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "yml", "yaml", "xml", "html", "htm",
            "css", "js", "mjs", "ts", "tsx", "jsx", "vue", "java", "kt", "py", "go", "rs", "c", "cpp",
            "h", "sql", "sh", "bat", "ini", "conf", "properties", "toml", "gradle", "dockerfile");

    /** 兼容个别模型把思考过程以 &lt;think&gt; 标签混进正文的情况 */
    private static final Pattern THINK_TAG = Pattern.compile("(?s)^\\s*<think>(.*?)</think>");

    private final AgentMapper agentMapper;
    private final DictService dictService;
    /** 只读复用附件模块的能力（getResource 拿校验过的真实路径），不新增 / 不修改附件接口 */
    private final AttachmentService attachmentService;

    /** 连接 5s / 读 180s：thinking 模型一轮可能要跑几十秒，读超时必须给足 */
    private final RestClient restClient = RestClient.builder()
            .requestFactory(requestFactory())
            .build();

    public AgentServiceImpl(AgentMapper agentMapper, DictService dictService,
                            AttachmentService attachmentService) {
        this.agentMapper = agentMapper;
        this.dictService = dictService;
        this.attachmentService = attachmentService;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(180_000);
        return factory;
    }

    /* ---------- 对话 ---------- */

    @Override
    public AgentChatVO chat(String userId, AgentChatRequest request) {
        String content = request.getContent() == null ? "" : request.getContent().trim();
        List<Attachment> attachments = resolveAttachments(request.getAttachmentIds());
        if (content.isEmpty() && attachments.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空（或附上至少一个附件）");
        }

        Map<String, String> cfg = agentConfig();
        String apiKey = cfg.getOrDefault("API Key", "").trim();
        if (apiKey.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "尚未配置 API Key：请到「系统管理 › 数据字典」编辑 agent_config 下的「API Key」");
        }
        String chatUrl = cfg.getOrDefault("chat_url", DEFAULT_CHAT_URL).trim();
        String model = resolveModel(request.getModel(), cfg);

        // 文本类附件把正文读进上下文；图片 / PDF / Office 只存档并给模型一句说明（GLM-5.3 仅支持文本输入）
        AttachmentContext attachmentContext = buildAttachmentContext(attachments);
        List<AgentAttachmentVO> attachmentVOs = attachmentContext.toVOs(attachments);
        String effectiveContent = attachmentContext.mergeInto(content);

        String titleSeed = content.isEmpty() && !attachments.isEmpty()
                ? "附件：" + attachments.get(0).getOriginalName() : content;
        AgentConversation conversation = resolveConversation(
                userId, request.getConversationId(), titleSeed, model);

        AgentMessage userMessage = new AgentMessage(
                newId(), conversation.getId(), "user", content,
                toJson(attachmentVOs), null, null, null, null, null, null);
        agentMapper.insertMessage(userMessage);

        // 组装多轮上下文：system 提示词 + 最近 20 条（最后一条是当前 user 消息，用含附件正文的版本）
        List<AgentMessage> recent = new ArrayList<>(
                agentMapper.selectRecentMessages(conversation.getId(), MAX_CONTEXT_MESSAGES));
        java.util.Collections.reverse(recent);
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = cfg.getOrDefault("system_prompt", "").trim();
        if (!systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (int i = 0; i < recent.size(); i++) {
            AgentMessage m = recent.get(i);
            boolean current = i == recent.size() - 1;
            String messageContent = current ? effectiveContent : m.getContent();
            if (!current && messageContent != null && !messageContent.isBlank()) {
                // 历史轮次不回读附件正文（省 IO / token），只标注当时带了哪些附件
                messageContent = messageContent + attachmentHint(m.getAttachments());
            }
            if (messageContent == null || messageContent.isBlank()) {
                messageContent = "（用户发送了附件）";
            }
            messages.add(Map.of("role", m.getRole(), "content", messageContent));
        }

        long start = System.currentTimeMillis();
        UpstreamReply reply = callUpstream(apiKey, chatUrl, model, messages);
        int costMs = (int) (System.currentTimeMillis() - start);

        AgentMessage assistantMessage = new AgentMessage(
                newId(), conversation.getId(), "assistant", reply.content(), null,
                emptyToNull(reply.reasoning()), model,
                reply.promptTokens(), reply.completionTokens(), costMs, null);
        agentMapper.insertMessage(assistantMessage);
        agentMapper.touchConversation(conversation.getId(), model, userId);

        return new AgentChatVO(conversation.getId(), conversation.getTitle(),
                toVO(userMessage), toVO(assistantMessage));
    }

    /**
     * 单次上游调用：发 messages、取回复；网络异常与上游错误码都翻译成业务异常。
     *
     * <p>响应按 String 接收、再用 Jackson 2 手动解析——Spring Boot 4 的 HTTP 消息转换器
     * 是 Jackson 3（tools.jackson），直接 body(JsonNode.class) 会让它去构造 Jackson 2 的
     * JsonNode 而炸掉（两套坐标系不通用）。自己解析还能兜住「HTTP 200 但 body 是 error」
     * 的非典型形态。</p>
     */
    private UpstreamReply callUpstream(String apiKey, String chatUrl, String model,
                                       List<Map<String, String>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);

        String raw;
        try {
            raw = restClient.post()
                    .uri(java.net.URI.create(chatUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw translateUpstreamError(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            // 覆盖连接失败 / 读超时等网络层问题——与业务码区分开
            log.warn("调用智谱平台网络异常: {}", e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "连接智谱平台失败或响应超时，请稍后重试：" + rootMessage(e));
        }

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智谱平台返回了空响应，请稍后重试");
        }
        JsonNode root;
        try {
            root = JsonSupport.MAPPER.readTree(raw);
        } catch (Exception parseError) {
            log.warn("解析智谱响应失败: {}", raw);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智谱平台返回了无法解析的响应，请稍后重试");
        }
        if (root.path("error").isObject()) {
            // HTTP 200 但 body 是错误结构：按上游错误统一翻译
            throw translateUpstreamError(200, raw);
        }

        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        String reasoning = message.hasNonNull("reasoning_content")
                ? message.get("reasoning_content").asText("") : "";
        if (content.isBlank() && reasoning.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "智谱平台返回了空回复，请重试或换一个模型");
        }

        // 兼容思考过程混进正文：<think>…</think> 拆出来
        Matcher matcher = THINK_TAG.matcher(content);
        if (matcher.find()) {
            if (reasoning.isBlank()) {
                reasoning = matcher.group(1).trim();
            }
            content = content.substring(matcher.end()).trim();
        }

        JsonNode usage = root.path("usage");
        return new UpstreamReply(
                content, reasoning,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    /** 上游错误 → 面向使用者的中文提示（附原始码，便于对文档） */
    private BusinessException translateUpstreamError(int status, String responseBody) {
        String code = "";
        String upstreamMessage = "";
        try {
            JsonNode error = JsonSupport.MAPPER.readTree(responseBody).path("error");
            code = error.path("code").asText("");
            upstreamMessage = error.path("message").asText("");
        } catch (Exception parseError) {
            log.warn("解析智谱错误响应失败: {}", responseBody);
        }

        String friendly;
        ResultCode resultCode;
        if (status == 401 || "1000".equals(code) || "1001".equals(code) || "1003".equals(code)) {
            resultCode = ResultCode.INTERNAL_ERROR; // 千万别映射 401：会触发前端登出
            friendly = "智谱 API 认证失败（code " + orDash(code) + "）：请到「系统管理 › 数据字典 › agent_config」检查 API Key";
        } else if ("1211".equals(code)) {
            resultCode = ResultCode.BAD_REQUEST;
            friendly = "模型不存在（1211）：请检查字典 agent_config 下 default_model 的取值";
        } else if ("1261".equals(code)) {
            resultCode = ResultCode.BAD_REQUEST;
            friendly = "对话内容过长（1261）：请新建会话，或删掉旧会话后再试";
        } else if ("1301".equals(code)) {
            resultCode = ResultCode.BAD_REQUEST;
            friendly = "内容被平台安全审核拦截（1301），请调整提问内容";
        } else if (status == 429) {
            resultCode = ResultCode.TOO_MANY_REQUESTS;
            friendly = "触发智谱平台限流或额度限制（code " + orDash(code) + "）：请稍后重试；若持续出现请检查账户余额与套餐";
        } else if (status >= 500) {
            resultCode = ResultCode.INTERNAL_ERROR;
            friendly = "智谱服务暂时不可用（HTTP " + status + "，code " + orDash(code) + "）：请稍后重试";
        } else {
            resultCode = ResultCode.INTERNAL_ERROR;
            friendly = "智谱 API 调用失败（HTTP " + status + "，code " + orDash(code) + "）：" + upstreamMessage;
        }
        log.warn("智谱上游错误: http={} code={} message={}", status, code, upstreamMessage);
        return new BusinessException(resultCode, friendly);
    }

    /* ---------- 会话管理 ---------- */

    @Override
    public List<AgentConversationVO> myConversations(String userId) {
        List<AgentConversation> conversations = agentMapper.selectConversationsByUser(userId, MAX_CONVERSATIONS);
        List<AgentConversationVO> result = new ArrayList<>(conversations.size());
        for (AgentConversation c : conversations) {
            result.add(new AgentConversationVO(c.getId(), c.getTitle(), c.getModel(), c.getUpdateTime()));
        }
        return result;
    }

    @Override
    public List<AgentMessageVO> messages(String userId, String conversationId) {
        requireOwner(userId, conversationId);
        List<AgentMessage> messages = agentMapper.selectMessagesAsc(conversationId);
        List<AgentMessageVO> result = new ArrayList<>(messages.size());
        for (AgentMessage m : messages) {
            result.add(toVO(m));
        }
        return result;
    }

    @Override
    public void deleteConversation(String userId, String conversationId) {
        requireOwner(userId, conversationId);
        agentMapper.logicDeleteConversation(conversationId, userId);
    }

    /* ---------- 内部工具 ---------- */

    /** 读字典：label → value。实时读取，数据字典里改完即生效 */
    private Map<String, String> agentConfig() {
        Map<String, String> cfg = new HashMap<>();
        for (DictItemVO item : dictService.listEnabledItems(DICT_TYPE)) {
            cfg.put(item.label(), item.value());
        }
        return cfg;
    }

    private String resolveModel(String requested, Map<String, String> cfg) {
        String model = (requested == null || requested.isBlank())
                ? cfg.getOrDefault("default_model", "glm-5.3").trim()
                : requested.trim();
        if (!ALLOWED_MODELS.contains(model)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的模型：" + model + "（可选 glm-5.3 / glm-5.3-flash）");
        }
        return model;
    }

    /** 取会话或新建；他人会话一律按不存在处理，避免探测别人的会话 ID */
    private AgentConversation resolveConversation(String userId, String conversationId,
                                                  String firstContent, String model) {
        if (conversationId != null && !conversationId.isBlank()) {
            AgentConversation conversation = agentMapper.selectConversationById(conversationId.trim());
            if (conversation == null || !conversation.getUserId().equals(userId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在或已被删除");
            }
            return conversation;
        }
        String title = firstContent.length() > TITLE_MAX_LENGTH
                ? firstContent.substring(0, TITLE_MAX_LENGTH) : firstContent;
        AgentConversation conversation = new AgentConversation(
                newId(), userId, title, model, "0", userId, userId, LocalDateTime.now(), LocalDateTime.now());
        agentMapper.insertConversation(conversation);
        return conversation;
    }

    private void requireOwner(String userId, String conversationId) {
        AgentConversation conversation = agentMapper.selectConversationById(
                conversationId == null ? "" : conversationId.trim());
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在或已被删除");
        }
    }

    private AgentMessageVO toVO(AgentMessage m) {
        return new AgentMessageVO(m.getId(), m.getRole(), m.getContent(), parseAttachments(m.getAttachments()),
                m.getReasoning(), m.getModel(), m.getPromptTokens(), m.getCompletionTokens(),
                m.getCostMs(), m.getCreateTime());
    }

    /* ---------- 附件 ---------- */

    /** 把前端带来的附件 ID 解析成附件记录；不存在 / 已删除 / 文件缺失由附件模块统一抛业务异常 */
    private List<Attachment> resolveAttachments(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Attachment> result = new ArrayList<>(ids.size());
        Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || !seen.add(id.trim())) {
                continue;
            }
            result.add(attachmentService.getResource(id.trim()).attachment());
        }
        return result;
    }

    /**
     * 读文本类附件正文拼进上下文；其余附件只给模型一句「无法解析」的说明。
     *
     * <p>GLM-5.3 官方文档明确「目前仅支持处理文本模态信息」，所以图片不能当视觉输入，
     * 硬塞 image_url 只会让请求失败。诚实的做法是：文件照常存档、消息里可见，
     * 模型侧明确告诉它「有这个附件但你看不到内容」，避免它顺着文件名编造。</p>
     */
    private AttachmentContext buildAttachmentContext(List<Attachment> attachments) {
        if (attachments.isEmpty()) {
            return new AttachmentContext("", Set.of(), List.of());
        }
        StringBuilder text = new StringBuilder();
        Set<String> parsed = new java.util.LinkedHashSet<>();
        long budget = MAX_ATTACHMENT_TOTAL_BYTES;

        for (Attachment attachment : attachments) {
            if (budget <= 0 || !isTextLike(attachment)) {
                continue;
            }
            try {
                AttachmentResource resource = attachmentService.getResource(attachment.getId());
                int limit = (int) Math.min(MAX_ATTACHMENT_BYTES, budget);
                String body = readTextPrefix(resource.path(), limit);
                if (body == null) {
                    continue;
                }
                boolean truncated = attachment.getFileSize() != null && attachment.getFileSize() > limit;
                text.append("\n\n===== 附件《").append(attachment.getOriginalName()).append("》开始 =====\n")
                        .append(body);
                if (truncated) {
                    text.append("\n…（附件过大，以上为前 ").append(limit / 1024).append("KB，已截断）");
                }
                text.append("\n===== 附件《").append(attachment.getOriginalName()).append("》结束 =====");
                parsed.add(attachment.getId());
                budget -= body.length();
            } catch (Exception e) {
                log.warn("读取附件正文失败，已跳过：id={}, name={}, 原因={}",
                        attachment.getId(), attachment.getOriginalName(), e.getMessage());
            }
        }

        List<String> unparsed = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (!parsed.contains(attachment.getId())) {
                unparsed.add(attachment.getOriginalName());
            }
        }
        if (!unparsed.isEmpty()) {
            text.append("\n\n[附件说明] 用户还附带了：").append(String.join("、", unparsed))
                    .append("。当前模型只能处理文本，无法读取其内容。不要臆测或编造这些文件的内容；")
                    .append("如需处理，请让用户把文本粘贴进对话。");
        }
        return new AttachmentContext(text.toString(), parsed, unparsed);
    }

    /** 文本类判定：MIME 优先，其次看扩展名（浏览器给的 contentType 常是空或 octet-stream） */
    private static boolean isTextLike(Attachment attachment) {
        String contentType = attachment.getContentType();
        if (contentType != null) {
            String type = contentType.toLowerCase();
            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")
                    || type.contains("yaml") || type.contains("javascript") || type.contains("x-sh")) {
                return true;
            }
            if (type.startsWith("image/") || type.contains("pdf") || type.contains("word")
                    || type.contains("excel") || type.contains("officedocument") || type.contains("zip")) {
                return false;
            }
        }
        String ext = attachment.getFileExt();
        return ext != null && TEXT_EXTENSIONS.contains(ext.toLowerCase());
    }

    /** 只读文件前 {@code limit} 字节并按 UTF-8 解码——附件可能很大，绝不能整读进内存 */
    private static String readTextPrefix(Path path, int limit) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(limit);
            if (bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "");
        } catch (IOException e) {
            return null;
        }
    }

    /** 历史轮次的附件提示：只给名字，不回读正文 */
    private static String attachmentHint(String attachmentsJson) {
        List<AgentAttachmentVO> list = parseAttachments(attachmentsJson);
        if (list.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(list.size());
        for (AgentAttachmentVO item : list) {
            names.add(item.name());
        }
        return "\n[本轮附带附件：" + String.join("、", names) + "]";
    }

    private static String toJson(List<AgentAttachmentVO> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return JsonSupport.MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<AgentAttachmentVO> parseAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonSupport.MAPPER.readValue(json, new TypeReference<List<AgentAttachmentVO>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 主键生成：UUID 去横线（与全库「应用层生成字符串主键」的约定一致） */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orDash(String code) {
        return code == null || code.isBlank() ? "-" : code;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** 上游一次调用的解析结果（内部载体） */
    private record UpstreamReply(String content, String reasoning, int promptTokens, int completionTokens) {
    }

    /**
     * 附件处理结果（内部载体）。
     *
     * @param text   要拼进本轮 user 消息的附件正文与说明（无附件时为空串）
     * @param parsed 正文已读入上下文的附件 ID
     * @param unparsed 只存档、模型看不到内容的附件名
     */
    private record AttachmentContext(String text, Set<String> parsed, List<String> unparsed) {

        /** 用户文本与附件正文合并：纯附件消息也能让模型有内容可依 */
        String mergeInto(String userContent) {
            if (text.isBlank()) {
                return userContent;
            }
            if (userContent.isBlank()) {
                return "以下是用户发送的附件内容。" + text;
            }
            return userContent + "\n\n以下是用户随消息发送的附件内容。" + text;
        }

        List<AgentAttachmentVO> toVOs(List<Attachment> attachments) {
            if (attachments.isEmpty()) {
                return List.of();
            }
            List<AgentAttachmentVO> result = new ArrayList<>(attachments.size());
            for (Attachment attachment : attachments) {
                result.add(new AgentAttachmentVO(attachment.getId(), attachment.getOriginalName(),
                        attachment.getContentType(), attachment.getFileSize(),
                        parsed.contains(attachment.getId())));
            }
            return result;
        }
    }
}
