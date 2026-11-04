package com.example.erupt.service;

import com.example.erupt.repository.entity.Attachment;

import java.nio.file.Path;

/**
 * 服务层内部使用的「可预览附件」载体。
 *
 * <p>与 {@link AttachmentResource} 的区别：预览涉及<b>安全策略裁决</b>，光有记录与路径不够，
 * 还需要把「能否内联、按什么类型输出、是否加沙箱」一并裁决好交给控制器，
 * 让视图层只负责拼响应头，不再重复这套判断。</p>
 *
 * @param attachment  附件记录（文件名、原始媒体类型等）
 * @param path        校验通过的真实文件路径
 * @param size        文件大小，用于 Content-Length
 * @param contentType <b>裁决后</b>的输出类型。可能是原始类型，也可能是降级结果
 *                    （如 text/html → text/plain，application/zip → application/octet-stream）
 * @param inline      是否可以内联渲染。false 时控制器仍返回流，但用 attachment 让浏览器下载
 * @param sandbox     是否额外加 {@code CSP sandbox}。仅对纯文本输出为 true
 */
public record AttachmentPreview(Attachment attachment,
                                Path path,
                                long size,
                                String contentType,
                                boolean inline,
                                boolean sandbox) {
}
