package com.example.erupt.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 发送消息请求。
 *
 * @param receiverId  接收人ID（单聊必填）
 * @param conversationId 会话ID（已有会话时优先用它，二选一即可）
 * @param content     消息正文
 * @param msgType     消息类型：1 文本，2 图片，3 文件
 */
public record SendMessageRequest(
        String receiverId,
        String conversationId,

        @Size(max = 4000, message = "消息不能超过 4000 个字符")
        String content,

        Integer msgType,

        /**
         * 附件ID（图片 / 文件消息）。
         *
         * <p>content 不再强制非空：图片消息可以只带 attachmentId。
         * "content 与 attachmentId 至少有一个"交由服务层校验——
         * 这种跨字段约束用注解表达反而更绕，而且错误提示也说不清是哪一项缺失。</p>
         */
        String attachmentId
) {
}
