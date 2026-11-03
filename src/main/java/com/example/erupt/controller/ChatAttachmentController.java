package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.vo.ChatAttachmentVO;
import com.example.erupt.repository.entity.ChatAttachment;
import com.example.erupt.service.ChatAttachmentService;
import com.example.erupt.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 聊天附件：图片与文件的上传、读取。
 *
 * <h3>与文件管理（{@code /api/attachments}）的关系</h3>
 *
 * <p><b>没有关系</b>，这是刻意的。两者是两条独立业务：</p>
 * <ul>
 *     <li>文件管理：按 {@code attachments} 菜单权限授权，有自己的列表、回收站、批量打包，
 *     数据在 {@code t_attachment} 表、{@code ./uploads} 目录</li>
 *     <li>聊天附件：任何登录用户都要用，随消息一起读，数据在 {@code t_chat_attachment} 表、
 *     {@code ./chat-uploads} 目录（与前者平级，不嵌套）</li>
 * </ul>
 *
 * <p>共用一张表会带来两个方向的相互影响：聊天里发的图会出现在文件列表里；
 * 文件列表清空回收站又会顺手带走聊天记录里的图。分开之后，两边谁也动不到谁。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/attachments")
public class ChatAttachmentController {

    private final ChatAttachmentService chatAttachmentService;
    private final ChatService chatService;

    public ChatAttachmentController(ChatAttachmentService chatAttachmentService, ChatService chatService) {
        this.chatAttachmentService = chatAttachmentService;
        this.chatService = chatService;
    }

    /**
     * 上传聊天附件（图片或任意文件）。字段名 {@code file}。
     *
     * <p>不在这里限制扩展名：聊天里发压缩包、PDF、表格都是正常需求。
     * 真正需要防的是"上传可执行内容后被浏览器当页面执行"，这靠响应侧的
     * {@code nosniff} 与 {@code Content-Disposition: attachment} 解决，而不是靠白名单，
     * 否则总会冒出"这个格式为什么不能发"的工单。</p>
     */
    @PostMapping
    public ResponseEntity<Result<ChatAttachmentVO>> upload(@RequestPart("file") MultipartFile file) {
        ChatAttachment saved = chatAttachmentService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(toVO(saved), "上传成功"));
    }

    /**
     * 读取聊天附件。
     *
     * <p>鉴权：上传者本人，或引用了该附件的会话成员。因此这个地址不能直接写进
     * {@code <img src>}——img 请求不带 Authorization 头，前端需先用带凭证的请求取回二进制。</p>
     *
     * <p>响应区分类型：图片以 {@code inline} 返回便于直接渲染，其余一律
     * {@code attachment}，让浏览器下载而不是尝试在当前域下解析执行。</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> read(@PathVariable String id) {
        String userId = AuthContext.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        chatService.requireAttachmentReadable(userId, id);

        ChatAttachmentService.ChatAttachmentResource resource = chatAttachmentService.open(id);
        ChatAttachment attachment = resource.attachment();
        boolean image = attachment.getContentType() != null
                && attachment.getContentType().startsWith("image/");
        StreamingResponseBody body = out -> chatAttachmentService.stream(resource, out);

        ContentDisposition disposition = (image
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(attachment.getOriginalName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(resolveMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(attachment.getFileSize()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 禁止 MIME 嗅探：防止浏览器把内容"猜"成 HTML 后在本站域下执行脚本
                .header("X-Content-Type-Options", "nosniff")
                // 附件内容按 id 固定不变，允许浏览器私有缓存，避免每次滚动都重新拉图
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(body);
    }

    private static ChatAttachmentVO toVO(ChatAttachment attachment) {
        return new ChatAttachmentVO(attachment.getId(), attachment.getOriginalName(),
                attachment.getFileSize(), attachment.getContentType());
    }

    /** 内容类型解析失败时退回二进制流，而不是让整个请求 500 */
    private static MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
