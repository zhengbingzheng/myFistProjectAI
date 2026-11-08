package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.config.AttachmentProperties;
import com.example.erupt.model.converter.AttachmentConverter;
import com.example.erupt.model.dto.AttachmentBatchDeleteRequest;
import com.example.erupt.model.dto.AttachmentQueryRequest;
import com.example.erupt.model.dto.BatchRestoreRequest;
import com.example.erupt.model.vo.AttachmentRecycleVO;
import com.example.erupt.model.vo.AttachmentVO;
import com.example.erupt.repository.entity.Attachment;
import com.example.erupt.repository.mapper.AttachmentMapper;
import com.example.erupt.service.AttachmentPreview;
import com.example.erupt.service.AttachmentResource;
import com.example.erupt.service.AttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 附件服务实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li><b>文件名不落库</b>：实际落盘名使用 string 主键 + 扩展名，原始文件名只作展示，
 *         同时阻断 {@code ../../} 这类路径穿越。</li>
 *     <li><b>流式读写</b>：下载与 ZIP 打包全程流式，不把完整内容读进内存。</li>
 *     <li><b>阈值前置校验</b>：多附件打包前先算总大小，超限直接拒绝，不产生半成品 ZIP。</li>
 *     <li><b>失败清理</b>：落盘后若入库失败会删掉已写入的文件，避免产生孤儿文件。</li>
 * </ul>
 */
@Slf4j
@Service
public class AttachmentServiceImpl implements AttachmentService {

    private static final AttachmentConverter CONVERTER = Mappers.getMapper(AttachmentConverter.class);

    /** 单个、批量上传的上限：避免一次请求塞入海量文件拖垮磁盘与数据库。 */
    private static final int MAX_BATCH_UPLOAD_SIZE = 100;

    /** 正常状态标记，与库表默认值保持一致。 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 回收站状态标记：附件被移入回收站后即为此值，只有在回收站再次删除才物理清除记录与文件。 */
    private static final String DEL_FLAG_DELETED = "1";

    /** 分页偏移量上限，超过后直接拒绝：LIMIT 的大 OFFSET 会扫描并丢弃大量行。 */
    private static final long MAX_OFFSET = 100_000L;

    /** 清空回收站的分批大小：避免一次性载入过多实体与拼出巨型 IN。 */
    private static final int RECYCLE_PURGE_BATCH_SIZE = 500;

    /** 允许的文件分类取值，用于校验查询参数是否合法。 */
    private static final Set<String> VALID_FILE_TYPES =
            Set.of("image", "document", "video", "audio", "archive", "other");

    /** 分类取值的提示文案。Set 的迭代顺序不稳定，故另用固定顺序的字符串回显给前端。 */
    private static final String VALID_FILE_TYPES_TEXT = "image/document/video/audio/archive/other";

    /** 可内联的媒体类型前缀：浏览器原生渲染、且不存在脚本执行上下文，是最安全的一类。 */
    private static final Set<String> INLINE_MEDIA_PREFIXES = Set.of("image/", "video/", "audio/");

    /** 可内联的具体类型：PDF 由浏览器内置阅读器渲染，没有外链脚本执行能力。 */
    private static final Set<String> INLINE_MEDIA_TYPES = Set.of("application/pdf");

    /**
     * 禁止内联的类型：SVG 本质是 XML，可携带 {@code <script>}；XHTML 同为可执行文档。
     *
     * <p>这类文件若内联，用户直接访问预览地址即可在同源上下文里执行脚本，
     * 能够读取本站 Cookie 并调用接口，因此强制走下载。</p>
     */
    private static final Set<String> NEVER_INLINE_TYPES =
            Set.of("image/svg+xml", "application/xhtml+xml");

    /**
     * 需要降级为纯文本输出的类型清单已收敛到 {@link com.example.erupt.service.AttachmentMediaTypes}：
     * 下载链路（控制器）与预览链路（这里）共用同一份裁决，避免两处清单漂移。
     */

    /** 文本预览的统一输出类型，显式带字符集，避免中文源码乱码。 */
    private static final String TEXT_PLAIN_UTF_8 = "text/plain;charset=UTF-8";

    /** 本地存储方式标记。 */
    private static final String STORAGE_TYPE_LOCAL = "local";

    /** 年月目录格式（如 2026/10），避免单目录文件过多。 */
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private static final Set<String> IMAGE_EXT =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "heic", "tiff");
    private static final Set<String> DOCUMENT_EXT =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "log",
                    "json", "xml", "yml", "yaml", "rtf", "properties", "ini");
    private static final Set<String> VIDEO_EXT =
            Set.of("mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v", "mpg", "mpeg");
    private static final Set<String> AUDIO_EXT =
            Set.of("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "aiff");
    private static final Set<String> ARCHIVE_EXT =
            Set.of("zip", "rar", "7z", "gz", "tar", "tgz", "bz2", "xz", "jar");

    private final AttachmentMapper attachmentMapper;
    private final AttachmentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Path storageRoot;

    public AttachmentServiceImpl(AttachmentMapper attachmentMapper, AttachmentProperties properties,
                                 TransactionTemplate transactionTemplate) {
        this.attachmentMapper = attachmentMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.storageRoot = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("附件存储根目录不可用：" + storageRoot, e);
        }
    }

    // 不加 @Transactional：本方法只做一次 INSERT（自动提交即可），落盘 IO 也不应包进数据库事务；
    // uploadBatch 以自调用方式复用本方法（绕开 AOP 代理），加注解反而会留下“看似有事务”的误解。
    @Override
    public AttachmentVO upload(MultipartFile file) {
        Objects.requireNonNull(file, "上传文件不能为空");
        requireValidFile(file);

        String originalName = safeOriginalName(file.getOriginalFilename());
        String ext = extensionOf(originalName);
        String id = UUID.randomUUID().toString().replace("-", "");

        Attachment entity = new Attachment();
        entity.setId(id);
        entity.setOriginalName(originalName);
        entity.setFileExt(ext);
        entity.setFileType(resolveFileType(ext, file.getContentType()));
        entity.setContentType(file.getContentType());
        entity.setStorageType(STORAGE_TYPE_LOCAL);
        entity.setDownloadCount(0L);
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setCreateBy(AuthContext.currentUsername());
        entity.setCreateTime(LocalDateTime.now());
        entity.setStoragePath(relativePath(id, ext));

        Path target = storageRoot.resolve(entity.getStoragePath()).normalize();
        boolean persisted = false;
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
                size = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            entity.setFileSize(size);
            entity.setFileHash(java.util.HexFormat.of().formatHex(digest.digest()));
            // 必须校验入库行数：insert 返回 0 表示记录并没有写进去。
            // 若此时仍按成功返回，前端拿到的 id 在库里根本不存在，后续下载/预览必然 404。
            // 这里抛异常会触发下面的 finally 清理已落盘文件，不会留下孤儿文件。
            int inserted = attachmentMapper.insert(entity);
            if (inserted != 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "附件入库失败，受影响行数 " + inserted + "：" + originalName);
            }
            persisted = true;
            log.info("附件上传成功，id={}, name={}, size={}, type={}", id, originalName, size, entity.getFileType());
        } catch (IOException | NoSuchAlgorithmException e) {
            // 异常原文可能是 FileSystemException（含服务器绝对路径/落盘文件名），只进日志
            log.warn("附件保存失败 id={} name={} 原因={}", id, originalName, e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "附件保存失败，请联系管理员");
        } finally {
            // 入库失败时回滚是这样不够的——文件已经落盘了，必须显式清理，否则会留下孤儿文件
            if (!persisted) {
                deleteQuietly(target);
            }
        }
        return CONVERTER.toVO(entity);
    }

    @Override
    public List<AttachmentVO> uploadBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件列表不能为空");
        }
        if (files.size() > MAX_BATCH_UPLOAD_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次批量上传不能超过 " + MAX_BATCH_UPLOAD_SIZE + " 个文件");
        }
        List<AttachmentVO> uploaded = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            uploaded.add(upload(file));
        }
        log.info("批量上传完成，共 {} 个文件", uploaded.size());
        return uploaded;
    }

    @Override
    public AttachmentResource getResource(String id) {
        Attachment attachment = requireAttachment(id);
        Path path = resolveSafePath(attachment.getStoragePath());
        requireFileExists(path, attachment.getOriginalName());
        return new AttachmentResource(attachment, path);
    }

    @Override
    public List<AttachmentResource> getResourcesForZip(List<String> ids) {
        List<String> distinctIds = requireValidIds(ids);
        List<Attachment> found = attachmentMapper.selectByIds(distinctIds);
        if (found.size() != distinctIds.size()) {
            log.warn("打包下载被拒绝，部分 id 不存在，ids={}, 实际存在={}", distinctIds, found.size());
            throw new BusinessException(ResultCode.NOT_FOUND, "待下载的附件中包含不存在的数据，未执行打包");
        }

        // 阈值校验必须在打包动作之前，避免先生成一个大 ZIP 才报错
        long totalSize = found.stream().mapToLong(attachment -> attachment.getFileSize() == null
                ? 0L : attachment.getFileSize()).sum();
        long limit = properties.zipMaxTotalSizeBytes();
        if (limit > 0 && totalSize > limit) {
            log.warn("打包下载被拒绝，总大小={} 字节超过阈值 {} 字节，ids={}", totalSize, limit, distinctIds);
            throw new BusinessException(ResultCode.BAD_REQUEST, ZIP_TOO_LARGE_MESSAGE);
        }

        List<AttachmentResource> resources = new ArrayList<>(found.size());
        for (Attachment attachment : found) {
            Path path = resolveSafePath(attachment.getStoragePath());
            requireFileExists(path, attachment.getOriginalName());
            resources.add(new AttachmentResource(attachment, path));
        }
        return resources;
    }

    @Override
    public void streamDownload(AttachmentResource resource, OutputStream out) {
        // 只有内容完整写出才计数：客户端中断/文件中途丢失的失败传输也 +1，
        // download_count 就失去统计意义
        if (copyTo(resource.path(), out)) {
            increaseDownloadCount(resource.attachment().getId());
        }
    }

    @Override
    public AttachmentPreview getPreview(String id) {
        // 复用下载链路的校验：存在、未进回收站、磁盘文件确实还在
        AttachmentResource resource = getResource(id);
        Attachment attachment = resource.attachment();
        String rawType = attachment.getContentType();
        String outputType = previewContentType(rawType);
        Long fileSize = attachment.getFileSize();
        return new AttachmentPreview(attachment, resource.path(),
                fileSize == null ? 0L : fileSize,
                outputType,
                supportsInline(rawType),
                TEXT_PLAIN_UTF_8.equals(outputType));
    }

    @Override
    public void streamPreview(AttachmentPreview preview, OutputStream out) {
        // 不计下载次数：预览会被频繁触发，计入会让 download_count 失去统计意义
        copyTo(preview.path(), out);
    }

    /**
     * 统一的流式写出：下载与预览共用，两者唯一的差别是是否计数。
     *
     * @return 是否完整写出。客户端中途断开（关页面、取消下载）返回 false——
     *         此时 200 响应头早已提交，包装成业务异常既改不了响应，
     *         还会把每次用户取消都污染成"附件读取失败"告警，故按正常事件静默收场。
     */
    private boolean copyTo(Path path, OutputStream out) {
        try (InputStream in = Files.newInputStream(path)) {
            in.transferTo(out);
            return true;
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.debug("客户端中断了附件传输：{}", e.getMessage());
                return false;
            }
            // 异常原文包含绝对路径，回显给调用方没有意义且泄露部署结构
            log.warn("附件读取失败 path={} 原因={}", path, e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "附件读取失败，请联系管理员");
        }
    }

    /** 判断 IO 异常是否由客户端主动断开引起（Tomcat 的 ClientAbortException、Broken pipe 等） */
    private static boolean isClientAbort(IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause.getClass().getName().contains("ClientAbort")) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 能否内联渲染：由服务端按媒体类型裁决，不接受客户端指定。
     *
     * <p>这里是预览功能唯一的安全关卡。一旦把决定权交给请求参数，
     * 上传者只要拿到一个 inline 的地址，就能让任意 html 在本站域下执行脚本。</p>
     */
    private boolean supportsInline(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (NEVER_INLINE_TYPES.stream().anyMatch(type::startsWith)) {
            return false;
        }
        return INLINE_MEDIA_TYPES.contains(type)
                || INLINE_MEDIA_PREFIXES.stream().anyMatch(type::startsWith)
                // 能按纯文本输出的类型，执行能力已被消除，可安全内联
                || com.example.erupt.service.AttachmentMediaTypes.isScriptableText(type);
    }

    /**
     * 裁决预览实际输出的媒体类型：可以等于原始类型，也可以是降级结果。
     */
    private String previewContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (!supportsInline(type)) {
            // 不可内联的类型（SVG、XHTML，以及浏览器渲染不了的 zip、docx…）统一按二进制流下载
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        // 可内联的文本类一律以纯文本输出：浏览器不会把纯文本当文档解析，脚本自然无从谈起
        if (com.example.erupt.service.AttachmentMediaTypes.isScriptableText(type)) {
            return TEXT_PLAIN_UTF_8;
        }
        return type;
    }

    @Override
    public void streamZip(List<AttachmentResource> resources, OutputStream out) {
        Set<String> usedEntryNames = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            for (AttachmentResource resource : resources) {
                String entryName = uniqueEntryName(resource.attachment().getOriginalName(), usedEntryNames);
                zos.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = Files.newInputStream(resource.path())) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
                increaseDownloadCount(resource.attachment().getId());
            }
        } catch (IOException e) {
            // 客户端取消下载与真正的 IO 故障要分开：前者是正常事件，已写出的条目有效
            if (isClientAbort(e)) {
                log.debug("客户端中断了 ZIP 打包下载：{}", e.getMessage());
                return;
            }
            // 打包中途出错时前端拿到的会是残缺的 ZIP，只能提示重传/重试
            log.warn("附件打包失败 原因={}", e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "附件打包失败，请重试");
        }
    }

    /** 单个文件校验：非空并满足大小上限。 */
    private void requireValidFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传的文件不能为空");
        }
        long maxBytes = properties.maxUploadSizeBytes();
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "文件大小超过上限，单个文件最大 " + properties.getMaxUploadSizeKb() + " KB");
        }
    }

    /** 批量下载入参校验：非空、数量上限、剔除 null 并去重。 */
    private List<String> requireValidIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待下载的 id 列表不能为空");
        }
        List<String> distinctIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待下载的 id 列表不能为空");
        }
        return distinctIds;
    }

    private Attachment requireAttachment(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "附件 id 不能为空");
        }
        Attachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在：" + id);
        }
        return attachment;
    }

    /**
     * 解析真实文件路径并阻断路径穿越：解析后的绝对路径必须仍在存储根目录之内。
     */
    private Path resolveSafePath(String relativePath) {
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "附件存储路径非法：" + relativePath);
        }
        return target;
    }

    private void requireFileExists(Path path, String originalName) {
        if (!Files.isRegularFile(path)) {
            // 记录还在但磁盘文件没了：属于数据不一致，明确报出来比返回空流好排查
            throw new BusinessException(ResultCode.NOT_FOUND, "附件文件已丢失：" + originalName);
        }
    }

    /**
     * 下载计数自增：同样判断数据库返回状态，但<b>不阻断主流程</b>。
     *
     * <p>受影响行数不等于 1 说明记录已不存在（可能刚被并发删除），需要记录下来供排查。
     * 但计数只是旁路统计，它的成败<b>不代表</b>下载本身的成败——文件内容已经完整写出，
     * 不能因为计数没加上就把一次成功的下载判为失败。</p>
     */
    private void increaseDownloadCount(String id) {
        try {
            int affected = attachmentMapper.incrementDownloadCount(id);
            if (affected != 1) {
                log.warn("下载计数未更新，id={}，受影响行数={}（记录可能已被删除）", id, affected);
            }
        } catch (Exception e) {
            log.warn("下载计数更新失败，id={}, msg={}", id, e.getMessage());
        }
    }

    /**
     * 取原始文件名中的纯文件名部分，抹掉可能携带的目录信息。
     */
    private String safeOriginalName(String originalFilename) {
        String name = (originalFilename == null || originalFilename.isBlank())
                ? "unnamed" : originalFilename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            // ".." 这类名字入库无害，但作为 ZIP 条目发给客户端后，
            // 个别解压器会把它当父目录处理，统一替换掉
            return "unnamed";
        }
        // schema 里 original_name 是 VARCHAR(255)：显式截断，
        // 不依赖数据库 sql_mode 是否严格（宽松模式下超长会被静默截断成乱码风险）
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 扩展名会拼进落盘路径与 ZIP 条目名，只接受小写字母数字。
        // 旧实现只限长度，`report.<script>` 这类文件名会把尖括号带进路径；
        // 与聊天附件模块的 [a-z0-9]{1,16} 保持同一规则。
        return ext.matches("[a-z0-9]{1,16}") ? ext : "";
    }

    /** 相对存储根目录的路径：年月目录 + 主键 + 扩展名。 */
    private String relativePath(String id, String ext) {
        String month = YearMonth.now().format(MONTH_DIR);
        return month + "/" + id + (ext.isEmpty() ? "" : "." + ext);
    }

    private String resolveFileType(String ext, String contentType) {
        if (!ext.isEmpty()) {
            if (IMAGE_EXT.contains(ext)) {
                return "image";
            }
            if (DOCUMENT_EXT.contains(ext)) {
                return "document";
            }
            if (VIDEO_EXT.contains(ext)) {
                return "video";
            }
            if (AUDIO_EXT.contains(ext)) {
                return "audio";
            }
            if (ARCHIVE_EXT.contains(ext)) {
                return "archive";
            }
        }
        if (contentType != null && !contentType.isBlank()) {
            String type = contentType.toLowerCase(Locale.ROOT);
            if (type.startsWith("image/")) {
                return "image";
            }
            if (type.startsWith("video/")) {
                return "video";
            }
            if (type.startsWith("audio/")) {
                return "audio";
            }
            if (type.contains("zip") || type.contains("compressed")) {
                return "archive";
            }
            if (type.startsWith("text/") || type.contains("pdf") || type.contains("word")
                    || type.contains("excel") || type.contains("sheet") || type.contains("presentation")
                    || type.contains("officedocument")) {
                return "document";
            }
        }
        return "other";
    }

    /** ZIP 内不允许同名条目，重复时追加序号。 */
    private String uniqueEntryName(String originalName, Set<String> usedEntryNames) {
        // 消费点再净化一次：上传侧 safeOriginalName 已经剥离路径，但 ZIP 条目名直接来自库里的
        // original_name，任何未来新增的写入路径（导入、修数）都可能绕过上传校验。
        // 防 Zip Slip 的边界应该放在"拼条目名"这一处，而不是依赖上游单点。
        String base = safeZipEntryName(originalName);
        String name = base;
        int seq = 1;
        while (!usedEntryNames.add(name)) {
            int dot = base.lastIndexOf('.');
            name = dot > 0 ? base.substring(0, dot) + "(" + seq + ")" + base.substring(dot)
                    : base + "(" + seq + ")";
            seq++;
        }
        return name;
    }

    /** 条目名净化：去掉目录分隔符、控制字符与 "." / ".."，避免解压端把条目当路径处理。 */
    static String safeZipEntryName(String originalName) {
        String name = originalName == null ? "" : originalName;
        name = name.replace('/', '_').replace('\\', '_')
                .replaceAll("[\\u0000-\\u001f\\u007f]", "_")
                .trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        return name;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("落盘文件的清理未能完成：path={}, msg={}", path, e.getMessage());
        }
    }

    @Override
    public PageResult<AttachmentVO> listNormal(AttachmentQueryRequest query) {
        QueryCondition condition = requireValidQuery(query);
        long total = attachmentMapper.countByCondition(condition.originalName(), condition.fileType(),
                condition.startTime(), condition.endTime(), DEL_FLAG_NORMAL);
        if (total == 0) {
            // 无数据时不查列表 SQL，直接给空页；分页参数仍原样回传，便于前端保持翻页状态
            return PageResult.of(List.of(), 0L, condition.page(), condition.pageSize());
        }
        long offset = offsetOf(condition);
        List<Attachment> records = attachmentMapper.selectPageByCondition(condition.originalName(),
                condition.fileType(), condition.startTime(), condition.endTime(),
                DEL_FLAG_NORMAL, offset, condition.pageSize());
        log.debug("查询正常附件：total={}, page={}, pageSize={}", total, condition.page(), condition.pageSize());
        return PageResult.of(CONVERTER.toVOList(records), total, condition.page(), condition.pageSize());
    }

    @Override
    public PageResult<AttachmentRecycleVO> listRecycled(AttachmentQueryRequest query) {
        QueryCondition condition = requireValidQuery(query);
        long total = attachmentMapper.countByCondition(condition.originalName(), condition.fileType(),
                condition.startTime(), condition.endTime(), DEL_FLAG_DELETED);
        if (total == 0) {
            return PageResult.of(List.of(), 0L, condition.page(), condition.pageSize());
        }
        long offset = offsetOf(condition);
        List<Attachment> records = attachmentMapper.selectPageByCondition(condition.originalName(),
                condition.fileType(), condition.startTime(), condition.endTime(),
                DEL_FLAG_DELETED, offset, condition.pageSize());
        log.debug("查询回收站附件：total={}, page={}, pageSize={}", total, condition.page(), condition.pageSize());
        return PageResult.of(CONVERTER.toRecycleVOList(records), total, condition.page(), condition.pageSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待恢复的附件 id 不能为空");
        }
        Attachment attachment = attachmentMapper.selectByIdAnyFlag(id);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在：" + id);
        }
        // 先判存在再判状态，两种失败要区分清楚：找不到是 404，找到了但状态不对是 409
        requireInRecycle(attachment);
        int affected = attachmentMapper.restoreByIds(List.of(id), LocalDateTime.now());
        // 该 SQL 带 del_flag='1' 前提：行数不足说明记录刚被并发改过，恢复其实没有生效
        requireAffected(affected, 1, "恢复附件");
        log.info("恢复附件成功，id={}, name={}", id, attachment.getOriginalName());
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<String> ids) {
        List<String> distinctIds = requireValidRestoreIds(ids);
        // 整批通过才执行：先确认每条都存在，再确认每条都在回收站，任一条不符就整批拒绝
        if (attachmentMapper.countByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "待恢复的附件中包含不存在的数据，未执行恢复");
        }
        if (attachmentMapper.countInRecycleByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "待恢复的附件中存在不处于回收站状态的数据，未执行恢复");
        }
        int affected = attachmentMapper.restoreByIds(distinctIds, LocalDateTime.now());
        // 整批语义下必须逐条对得上：差一条说明有记录被并发改过，此时整批回滚
        requireAffected(affected, distinctIds.size(), "批量恢复附件");
        log.info("批量恢复附件成功，请求 {} 条，实际恢复 {} 条", distinctIds.size(), affected);
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveToRecycle(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的附件 id 不能为空");
        }
        Attachment attachment = attachmentMapper.selectByIdAnyFlag(id);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在：" + id);
        }
        // 已在回收站时明确冲突：重复删除若静默成功，用户会以为发生了别的动作
        requireInNormal(attachment);
        LocalDateTime now = LocalDateTime.now();
        int affected = attachmentMapper.moveToRecycleByIds(List.of(id), now, expireTimeOf(now));
        // 该 SQL 带 del_flag='0' 前提：行数不足说明记录刚被并发改过，删除其实没有生效
        requireAffected(affected, 1, "删除附件");
        log.info("附件移入回收站，id={}, name={}", id, attachment.getOriginalName());
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveToRecycleBatch(List<String> ids) {
        List<String> distinctIds = requireValidDeleteIds(ids);
        // 与恢复对称：先确认每条都存在，再确认每条都处于正常状态，任一条不符就整批拒绝
        if (attachmentMapper.countByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "待删除的附件中包含不存在的数据，未执行删除");
        }
        if (attachmentMapper.countNormalByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "待删除的附件中存在已在回收站的数据，未执行删除");
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = attachmentMapper.moveToRecycleByIds(distinctIds, now, expireTimeOf(now));
        requireAffected(affected, distinctIds.size(), "批量删除附件");
        log.info("批量移入回收站，请求 {} 条，实际 {} 条", distinctIds.size(), affected);
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purge(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待彻底删除的附件 id 不能为空");
        }
        Attachment attachment = attachmentMapper.selectByIdAnyFlag(id);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在：" + id);
        }
        // 只删回收站里的：正常状态的附件必须先移入回收站，避免一步操作造成不可逆丢失
        requireRecycledForPurge(attachment);
        return purgeRecycled(List.of(attachment));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeBatch(List<String> ids) {
        List<String> distinctIds = requireValidDeleteIds(ids);
        if (attachmentMapper.countByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "待彻底删除的附件中包含不存在的数据，未执行删除");
        }
        if (attachmentMapper.countInRecycleByIds(distinctIds) != distinctIds.size()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "待彻底删除的附件中存在不处于回收站的数据，未执行删除");
        }
        return purgeRecycled(attachmentMapper.selectRecycledByIds(distinctIds));
    }

    @Override
    public int emptyRecycle() {
        // 分批清理：回收站没有自动到期任务，可能积压数万条——
        // 一次性载入全部实体再拼单条巨型 IN 会同时顶到内存与 JDBC 占位符上限（65535）。
        // 每批用独立事务完成"删记录→提交后删文件"：批间无耦合才真正成立，
        // 任一批失败只回滚该批，事务持锁时间也不随积压量增长。
        // （方法级 @Transactional 会把全部批次圈进一个大事务，与分批设计自相矛盾，故不使用）
        int affected = 0;
        while (true) {
            List<Attachment> batch = attachmentMapper.selectRecycledBatch(RECYCLE_PURGE_BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            Integer purged = transactionTemplate.execute(status -> purgeRecycled(batch));
            affected += purged == null ? 0 : purged;
        }
        log.info("清空回收站完成，共 {} 条", affected);
        return affected;
    }

    /**
     * 物理删除的收尾：先删库里的记录，再清理磁盘文件。
     *
     * <p>这里查出的记录在调用入口已经做过存在性与状态校验，
     * 因此只需要负责"删记录 + 删文件"这两件无法原子化的事。</p>
     */
    private int purgeRecycled(List<Attachment> attachments) {
        List<String> ids = attachments.stream().map(Attachment::getId).toList();
        int affected = attachmentMapper.deleteRecycledByIds(ids);
        // 行数不足时抛异常，事务随之回滚：此时绝不能继续删文件——
        // 记录还留在库里却把磁盘文件删掉，会造成无法自愈的数据不一致。
        requireAffected(affected, ids.size(), "彻底删除附件");
        // 记录刚被删掉，路径必须在这之前算好（resolveSafePath 不查库，但语义上要趁早）
        List<Path> files = attachments.stream()
                .map(item -> resolveSafePath(item.getStoragePath()))
                .toList();
        deleteFilesAfterCommit(files);
        log.info("彻底删除附件，请求 {} 条，实际删除记录 {} 条", ids.size(), affected);
        return affected;
    }

    /**
     * 事务<b>提交之后</b>再清理物理文件。
     *
     * <p>顺序必须是「先删记录，再删文件」。反过来一旦写入失败，
     * 数据库会留下指向不存在文件的记录——这种不一致既查不出也自愈不了；
     * 当前顺序下最坏只是留下一个孤儿文件，可通过巡检发现并清理。</p>
     *
     * <p>之所以要等 afterCommit 而不是顺手删：文件 IO 不该被包在数据库事务里，
     * 慢速磁盘会白白占住连接；而且文件删除失败也不应回滚已经生效的记录删除。</p>
     */
    private void deleteFilesAfterCommit(List<Path> paths) {
        if (paths.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFilesQuietly(paths);
                }
            });
        } else {
            // 没有活动事务（例如单测直接调用）时立即清理，行为保持一致
            deleteFilesQuietly(paths);
        }
    }

    private void deleteFilesQuietly(List<Path> paths) {
        paths.forEach(this::deleteQuietly);
    }

    /**
     * 校验数据库写操作的受影响行数：与预期不符即判定业务失败并抛异常（事务随之回滚）。
     *
     * <p>写操作的返回值不能丢。MyBatis 返回的是<b>实际改动的行数</b>，而本模块的写 SQL
     * 大多带状态前提（如 {@code del_flag = '0'}），行数不足意味着记录已被并发改过、
     * 或状态已不是校验时所见的那样——此时业务并没有真正执行成功。若照常返回成功，
     * 前端会以为操作生效，实际数据却没变。</p>
     *
     * @param affected 数据库返回的实际受影响行数
     * @param expected 预期受影响行数
     * @param action   操作名，用于拼错误提示
     * @throws BusinessException 行数与预期不符（409：数据状态已被并发修改）
     */
    private void requireAffected(int affected, int expected, String action) {
        if (affected != expected) {
            log.warn("{}失败：预期影响 {} 行，实际 {} 行，事务将回滚", action, expected, affected);
            throw new BusinessException(ResultCode.CONFLICT,
                    action + "失败：数据状态已被其他操作修改，请刷新后重试"
                            + "（预期影响 " + expected + " 条，实际 " + affected + " 条）");
        }
    }

    /** 回收站到期时间：由配置的天数推算，供前端提示"还剩多久被清理"。 */
    private LocalDateTime expireTimeOf(LocalDateTime from) {
        long days = properties.getRecycleRetentionDays();
        return days > 0 ? from.plusDays(days) : from;
    }

    /** 移入回收站的前提：记录必须处于正常状态。 */
    private void requireInNormal(Attachment attachment) {
        if (!DEL_FLAG_NORMAL.equals(attachment.getDelFlag())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "附件已在回收站中，无需重复删除：" + attachment.getOriginalName());
        }
    }

    /** 批量删除入参校验：剔除空值、去重、数量上限。 */
    private List<String> requireValidDeleteIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        List<String> distinctIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        if (distinctIds.size() > AttachmentBatchDeleteRequest.MAX_BATCH_DELETE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次批量删除不能超过 " + AttachmentBatchDeleteRequest.MAX_BATCH_DELETE_SIZE + " 条");
        }
        return distinctIds;
    }

    /**
     * 校验并归一化查询条件：修正分页取值、转义模糊关键字、校验分类与时间区间是否合法。
     *
     * @return 可直接下发给 Mapper 的条件
     */
    private QueryCondition requireValidQuery(AttachmentQueryRequest query) {
        Objects.requireNonNull(query, "查询条件不能为空");
        String fileType = normalize(query.fileType());
        if (fileType != null && !VALID_FILE_TYPES.contains(fileType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "非法的文件分类：" + fileType + "，可选值：" + VALID_FILE_TYPES_TEXT);
        }
        LocalDateTime startTime = query.startTime();
        LocalDateTime endTime = query.endTime();
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
        return new QueryCondition(LikeEscape.escape(normalize(query.originalName())), fileType,
                startTime, endTime, query.pageOrDefault(), query.pageSizeOrDefault());
    }

    /**
     * 计算偏移量并做深分页保护。
     *
     * <p>页码超出实际页数<b>不在这里拦</b>——那是正常查询，只是结果为空；
     * 这里只拦住"偏移量本身大到会拖垮数据库"的请求。</p>
     */
    private long offsetOf(QueryCondition condition) {
        long offset = (long) (condition.page() - 1) * condition.pageSize();
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "分页偏移量过大，请缩小页码或改用更精确的查询条件");
        }
        return offset;
    }

    /** 恢复的前提：记录必须处于回收站。已是正常状态时明确报错，而不是静默当成成功。 */
    private void requireInRecycle(Attachment attachment) {
        if (!DEL_FLAG_DELETED.equals(attachment.getDelFlag())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "附件不在回收站中，无需恢复：" + attachment.getOriginalName());
        }
    }

    /**
     * 彻底删除的前提：记录必须处于回收站。
     *
     * <p>与恢复是同一个状态判断，但提示文案必须区分开。
     * 复用 {@link #requireInRecycle} 会让彻底删除失败时提示"无需恢复"，
     * 用户完全无法理解自己刚才点了什么、为什么失败。</p>
     */
    private void requireRecycledForPurge(Attachment attachment) {
        if (!DEL_FLAG_DELETED.equals(attachment.getDelFlag())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "附件不在回收站中，不能彻底删除：" + attachment.getOriginalName()
                            + "（请先移入回收站）");
        }
    }

    /** 批量恢复入参校验：剔除空值、去重、数量上限。 */
    private List<String> requireValidRestoreIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待恢复的 id 列表不能为空");
        }
        List<String> distinctIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待恢复的 id 列表不能为空");
        }
        if (distinctIds.size() > BatchRestoreRequest.MAX_BATCH_RESTORE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次批量恢复不能超过 " + BatchRestoreRequest.MAX_BATCH_RESTORE_SIZE + " 条");
        }
        return distinctIds;
    }

    /** 去空白，空串统一转成 null，便于 Mapper 的 {@code <if test="x != null">} 判断。 */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 归一化后的查询条件载体。
     *
     * <p>把"解析/校验"与"执行查询"拆开，保证列表与总数用的是同一份条件，
     * 不会出现两边对同一入参各自理解不同的问题。</p>
     */
    private record QueryCondition(String originalName,
                                  String fileType,
                                  LocalDateTime startTime,
                                  LocalDateTime endTime,
                                  int page,
                                  int pageSize) {
    }
}
