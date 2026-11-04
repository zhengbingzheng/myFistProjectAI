package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.ResultCode;
import com.example.erupt.repository.entity.ChatAttachment;
import com.example.erupt.repository.mapper.ChatAttachmentMapper;
import com.example.erupt.service.ChatAttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 聊天附件存储实现。
 *
 * <p>存储布局：{@code <root>/yyyy/MM/dd/<id>.<ext>}，根目录由
 * {@code app.chat.attachment.storage-dir} 指定，默认 {@code ./chat-uploads}，
 * 与文件管理的 {@code ./uploads} 平级而非嵌套。按日期分目录是为了让单目录文件数可控——
 * 聊天附件只增不减，全平铺在一个目录里，几个月后 ls 都会变慢。</p>
 */
@Slf4j
@Service
public class ChatAttachmentServiceImpl implements ChatAttachmentService {

    /** 图片上限：要在会话里反复加载，单张太大会拖慢渲染 */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** 普通文件上限 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final ChatAttachmentMapper attachmentMapper;
    private final Path storageRoot;

    public ChatAttachmentServiceImpl(ChatAttachmentMapper attachmentMapper,
                                     @Value("${app.chat.attachment.storage-dir:./chat-uploads}") String storageDir,
                                     @Value("${app.attachment.storage-dir:./uploads}") String fileStorageDir) {
        this.attachmentMapper = attachmentMapper;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();

        // 聊天附件目录不能落在文件管理目录里（也不能是同一个）：运维的备份/清理/迁移
        // 都以文件管理根目录为界，一旦嵌套就会把聊天附件一起卷走，两个功能又缠在一起了。
        // 这种错配从日志里很难看出来，所以在启动时直接失败，让配置问题当场暴露。
        Path fileStorageRoot = Path.of(fileStorageDir).toAbsolutePath().normalize();
        if (storageRoot.startsWith(fileStorageRoot)) {
            throw new IllegalStateException(String.format(
                    "聊天附件目录 %s 位于文件管理目录 %s 内，两者必须互不包含。"
                            + "请调整 app.chat.attachment.storage-dir。", storageRoot, fileStorageRoot));
        }

        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("聊天附件存储目录不可用：" + storageRoot, e);
        }
        log.info("聊天附件存储根目录：{}（文件管理目录：{}）", storageRoot, fileStorageRoot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatAttachment upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String contentType = file.getContentType();
        String originalName = safeName(file.getOriginalFilename());
        // 根据扩展名矫正客户端声明：SVG 无论被声明成 image/png 还是别的什么，
        // 都按真实类型落库，读取端（与文件管理模块同一规则）才能可靠地强制附件下载
        String ext = extensionOf(originalName);
        if ("svg".equalsIgnoreCase(ext)) {
            contentType = "image/svg+xml";
        }
        boolean image = contentType != null && contentType.startsWith("image/");
        long limit = image ? MAX_IMAGE_BYTES : MAX_FILE_BYTES;
        if (file.getSize() > limit) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    image ? "图片不能超过 10MB" : "文件不能超过 20MB");
        }

        // 上传者必须落库：它是判断"谁能把这条附件作为消息发出去"的唯一依据
        String uploaderId = AuthContext.currentUserId();
        if (uploaderId == null || uploaderId.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String relativePath = LocalDate.now().format(DATE_DIR) + "/" + id + (ext.isEmpty() ? "" : "." + ext);

        Path target = storageRoot.resolve(relativePath).normalize();
        // 路径全部由服务端拼装（id + 扩展名），理论上不可能越界；这里仍然校验，
        // 是因为扩展名来自用户，一旦将来放开"保留原扩展名大小写/多段"的改动就会变成真漏洞
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非法的文件路径");
        }

        boolean persisted = false;
        try {
            Files.createDirectories(target.getParent());
            long size = Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            ChatAttachment entity = new ChatAttachment();
            entity.setId(id);
            entity.setOriginalName(originalName);
            entity.setFileExt(ext);
            entity.setContentType(contentType);
            entity.setFileSize(size);
            entity.setStoragePath(relativePath);
            entity.setUploaderId(uploaderId);
            entity.setDelFlag("0");
            entity.setCreateTime(LocalDateTime.now());

            if (attachmentMapper.insert(entity) != 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "附件记录写入失败");
            }
            persisted = true;
            // 方法体成功 ≠ 事务一定成功：提交发生在方法返回之后（连接中断、锁超时等），
            // 提交失败时记录回滚而磁盘文件已落盘——注册回滚回调补删，
            // 不给"只增不减"的孤儿文件再开一个口子
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                if (status == STATUS_ROLLED_BACK || status == STATUS_UNKNOWN) {
                                    deleteQuietly(target);
                                    log.warn("聊天附件事务未提交，已清理落盘文件 id={}", id);
                                }
                            }
                        });
            }
            log.info("聊天附件上传成功 id={} name={} size={}B uploader={}", id, originalName, size, uploaderId);
            return entity;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "附件保存失败：" + e.getMessage());
        } finally {
            // 入库失败时文件已经落盘，必须显式清理，否则留下永远查不到的孤儿文件
            if (!persisted) {
                deleteQuietly(target);
            }
        }
    }

    @Override
    public ChatAttachment getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return attachmentMapper.selectById(id);
    }

    @Override
    public ChatAttachmentResource open(String id) {
        ChatAttachment attachment = getById(id);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在或已被删除");
        }
        Path path = storageRoot.resolve(attachment.getStoragePath()).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件文件已丢失");
        }
        return new ChatAttachmentResource(attachment, path);
    }

    @Override
    public void stream(ChatAttachmentResource resource, OutputStream out) {
        try {
            Files.copy(resource.path(), out);
        } catch (IOException e) {
            // 响应已经开始写，这里没法再改成错误状态码，只能记录
            log.warn("聊天附件写出中断 id={}：{}", resource.attachment().getId(), e.getMessage());
        }
    }

    /** 文件名只用于展示与下载，去掉任何路径成分，避免把目录结构带进来 */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未命名文件";
        }
        // 浏览器可能带上完整路径（旧版 IE / 部分客户端），只取最后一段
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "未命名文件";
        }
        // 数据库列宽 255，留出余量
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        // 扩展名只允许字母数字，避免拼进路径时带上奇怪字符
        return ext.matches("[a-z0-9]{1,16}") ? ext : "";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理聊天附件失败：{}", path, e);
        }
    }
}
