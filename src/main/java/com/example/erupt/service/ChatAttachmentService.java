package com.example.erupt.service;

import com.example.erupt.repository.entity.ChatAttachment;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.file.Path;

/**
 * 聊天附件的存取。
 *
 * <p>与 {@link AttachmentService} 完全独立：独立的表、独立的存储目录、独立的权限口径。
 * 文件管理模块的回收站、批量打包、清空等操作碰不到聊天附件，反之亦然。</p>
 */
public interface ChatAttachmentService {

    /**
     * 保存一个聊天附件。
     *
     * <p>接受任意类型：图片按图片消息渲染，其它按文件卡片展示。
     * 大小上限按类型区分（图片更严格），见实现类常量。</p>
     */
    ChatAttachment upload(MultipartFile file);

    /** 按 id 取附件元信息；不存在或已删除返回 null */
    ChatAttachment getById(String id);

    /** 取附件实体与磁盘路径，供流式输出；附件或文件缺失时抛业务异常 */
    ChatAttachmentResource open(String id);

    /** 写出文件内容 */
    void stream(ChatAttachmentResource resource, OutputStream out);

    /**
     * 附件实体 + 已校验过的磁盘路径。
     *
     * <p>路径在 {@link #open(String)} 里就做过越界校验（必须落在存储根目录内），
     * 调用方拿到即可直接读，不必再校验一次。</p>
     */
    record ChatAttachmentResource(ChatAttachment attachment, Path path) {
    }
}
