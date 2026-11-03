package com.example.erupt.controller;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.AttachmentBatchDeleteRequest;
import com.example.erupt.model.dto.AttachmentQueryRequest;
import com.example.erupt.model.dto.BatchDownloadRequest;
import com.example.erupt.model.dto.BatchRestoreRequest;
import com.example.erupt.model.vo.AttachmentRecycleVO;
import com.example.erupt.model.vo.AttachmentVO;
import com.example.erupt.repository.entity.Attachment;
import com.example.erupt.service.AttachmentPreview;
import com.example.erupt.service.AttachmentResource;
import com.example.erupt.service.AttachmentService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 附件接口（视图层）：只做参数绑定、响应头组装，业务与文件操作下沉到 {@link AttachmentService}。
 *
 * <p>上传用 {@code multipart/form-data}；下载返回流而非 Base64，避免大文件占用过多内存。</p>
 */
@RestController
@RequestMapping("/api/attachments")
@Validated
public class AttachmentController {

    /** ZIP 文件名时间戳格式，避免 Windows 下不允许的文件名字符 */
    private static final DateTimeFormatter ZIP_NAME_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 查询参数的起止时间格式，前端需按此格式传值，否则返回 400 并提示参数类型不正确 */
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 上传单个附件：字段名 {@code file}，返回含主键标识的附件信息。
     */
    @PostMapping("/upload")
    public ResponseEntity<Result<AttachmentVO>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(attachmentService.upload(file), "上传成功"));
    }

    /**
     * 上传多个附件：字段名 {@code files}，返回每个附件的标识列表。
     */
    @PostMapping("/upload/batch")
    public ResponseEntity<Result<List<AttachmentVO>>> uploadBatch(@RequestPart("files") List<MultipartFile> files) {
        List<AttachmentVO> uploaded = attachmentService.uploadBatch(files);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(uploaded, "上传成功，共 " + uploaded.size() + " 个文件"));
    }

    /**
     * 下载单个附件：直接返回文件流，浏览器可据 Content-Type 决定预览还是另存。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        AttachmentResource resource = attachmentService.getResource(id);
        Attachment attachment = resource.attachment();
        StreamingResponseBody body = out -> attachmentService.streamDownload(resource, out);
        return ResponseEntity.ok()
                .contentType(resolveMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition("attachment", attachment.getOriginalName()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(sizeOf(attachment)))
                .body(body);
    }

    /**
     * 在线预览：返回 {@code Content-Disposition: inline} 的流，供 img / video / iframe 直接渲染。
     *
     * <p><b>是否 inline 不接受请求参数指定</b>，而是服务端按媒体类型裁决的结果，
     * 原因见 {@link com.example.erupt.service.AttachmentService#getPreview(String)}：
     * 若让前端传 {@code inline=true}，上传者就能让任意 html 在本站域下执行脚本。</p>
     *
     * <p>裁决结果体现在响应上：</p>
     * <ul>
     *     <li>图片、音视频、PDF → inline，浏览器直接渲染</li>
     *     <li>文本类（含 html）→ inline，但按纯文本输出，展示的是源码而非渲染结果</li>
     *     <li>SVG、zip 等不在白名单的类型 → 仍是 attachment，浏览器转为下载；前端不必再分支判断</li>
     * </ul>
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable String id) {
        AttachmentPreview preview = attachmentService.getPreview(id);
        StreamingResponseBody body = out -> attachmentService.streamPreview(preview, out);
        return ResponseEntity.ok()
                .contentType(resolveMediaType(preview.contentType()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(preview.size()))
                // 禁止 MIME 嗅探：防止浏览器把内容"猜"成 HTML 后执行其中的脚本
                .header("X-Content-Type-Options", "nosniff")
                // 禁止加载任何子资源；仅对文本类再叠加 sandbox
                // （图片/PDF 若带 sandbox 可能影响浏览器内置阅读器正常工作）
                .header("Content-Security-Policy", preview.sandbox()
                        ? "default-src 'none'; sandbox"
                        : "default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition(preview.inline() ? "inline" : "attachment",
                                preview.attachment().getOriginalName()))
                .body(body);
    }

    /**
     * 下载多个附件：打包为 ZIP 流式返回。
     *
     * <p>总大小超过 {@code app.attachment.zip-max-total-size-kb} 时，服务层会在打包之前
     * 抛出业务异常，不会产出任何 ZIP 数据。</p>
     */
    @PostMapping("/download/zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@Valid @RequestBody BatchDownloadRequest request) {
        List<AttachmentResource> resources = attachmentService.getResourcesForZip(request.ids());
        StreamingResponseBody body = out -> attachmentService.streamZip(resources, out);
        String zipName = "attachments-" + LocalDateTime.now().format(ZIP_NAME_TIME_FORMATTER) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition("attachment", zipName))
                .body(body);
    }

    /**
     * 正常状态附件分页列表。
     *
     * <p>所有过滤条件均为可选。分页参数非法（0、负数、超上限）由服务层修正为默认值，
     * 不按错误处理；页码超出实际页数时返回空列表并带上真实总数，前端可据此跳回合理页。</p>
     *
     * @param originalName 文件名模糊匹配，支持部分匹配
     * @param fileType     分类精确匹配，取值 image/document/video/audio/archive/other
     * @param startTime    上传时间起，格式 {@code yyyy-MM-dd HH:mm:ss}，含边界
     * @param endTime      上传时间止，格式同上，含边界；早于 startTime 时返回 400
     * @param page         页码，从 1 开始，默认 1
     * @param pageSize     每页条数，默认 10，上限 100
     */
    @GetMapping
    public ResponseEntity<Result<PageResult<AttachmentVO>>> page(
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime endTime,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        AttachmentQueryRequest query =
                new AttachmentQueryRequest(originalName, fileType, startTime, endTime, page, pageSize);
        return ResponseEntity.ok(Result.success(attachmentService.listNormal(query)));
    }

    /**
     * 回收站附件分页列表，查询条件与正常列表完全一致。
     *
     * <p>差异只在数据范围（{@code del_flag = '1'}）、排序（按删除时间倒序）
     * 与返回结果（多带 {@code deleteTime}、{@code recycleExpireTime}）。</p>
     */
    @GetMapping("/recycle")
    public ResponseEntity<Result<PageResult<AttachmentRecycleVO>>> pageRecycled(
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime endTime,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        AttachmentQueryRequest query =
                new AttachmentQueryRequest(originalName, fileType, startTime, endTime, page, pageSize);
        return ResponseEntity.ok(Result.success(attachmentService.listRecycled(query)));
    }

    /**
     * 恢复单个附件：回收站 → 正常状态。
     *
     * <p>失败原因通过 {@link Result#message()} 给出：
     * 附件不存在（404）、附件不处于回收站（409，即本来就是正常状态）。</p>
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<Result<Void>> restore(@PathVariable String id) {
        attachmentService.restore(id);
        return ResponseEntity.ok(Result.success(null, "恢复成功"));
    }

    /**
     * 批量恢复附件：ids 中任一条不存在或不处于回收站，则<b>整批拒绝</b>，不产生部分恢复。
     *
     * <p>成功时 {@code message} 给出实际恢复条数（服务端会先去重）；
     * 失败时为 404（含不存在 id）或 409（含非回收站 id）。</p>
     */
    @PostMapping("/restore/batch")
    public ResponseEntity<Result<Void>> restoreBatch(@Valid @RequestBody BatchRestoreRequest request) {
        // 用数据库实际影响行数回执，而不是入参条数：两者不一致时服务层已抛异常并回滚
        int restored = attachmentService.restoreBatch(request.ids());
        return ResponseEntity.ok(Result.success(null, "已恢复 " + restored + " 个附件"));
    }

    /**
     * 删除单个附件 = <b>移入回收站</b>（可逆），文件仍保留在磁盘上。
     *
     * <p>路由说明：本端点路径是 {@code /{id}}，与 {@link #emptyRecycle} 的 {@code /recycle}
     * 同为两段路径；Spring 会优先匹配字面量更具体的 {@code /recycle}，
     * 因此 {@code DELETE /api/attachments/recycle} 不会落到这里。</p>
     *
     * <p>失败语义：404 附件不存在 / 409 附件已在回收站。</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> remove(@PathVariable String id) {
        attachmentService.moveToRecycle(id);
        return ResponseEntity.ok(Result.success(null, "已删除，可在回收站恢复"));
    }

    /**
     * 批量删除 = 批量移入回收站：ids 中任一条不存在或已在回收站，则<b>整批不执行</b>。
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Result<Void>> removeBatch(@Valid @RequestBody AttachmentBatchDeleteRequest request) {
        int deleted = attachmentService.moveToRecycleBatch(request.ids());
        return ResponseEntity.ok(Result.success(null, "已删除 " + deleted + " 个附件，可在回收站恢复"));
    }

    /**
     * <b>彻底删除</b>单个附件：连同数据库记录与磁盘文件一起清除，不可恢复。
     *
     * <p>只对<b>已处于回收站</b>的附件生效。对正常状态的附件调用会返回 409——
     * 必须先经 {@link #remove} 移入回收站，两级删除是防止误删的唯一防线。</p>
     */
    @DeleteMapping("/recycle/{id}")
    public ResponseEntity<Result<Void>> purge(@PathVariable String id) {
        attachmentService.purge(id);
        return ResponseEntity.ok(Result.success(null, "已彻底删除"));
    }

    /**
     * 批量彻底删除：ids 中任一条不在回收站则整批不执行，避免误删正常附件。
     */
    @DeleteMapping("/recycle/batch")
    public ResponseEntity<Result<Void>> purgeBatch(@Valid @RequestBody AttachmentBatchDeleteRequest request) {
        int purged = attachmentService.purgeBatch(request.ids());
        return ResponseEntity.ok(Result.success(null, "已彻底删除 " + purged + " 个附件"));
    }

    /**
     * 清空回收站：删除全部回收站附件及其文件。回收站为空时不视为错误。
     */
    @DeleteMapping("/recycle")
    public ResponseEntity<Result<Void>> emptyRecycle() {
        int purged = attachmentService.emptyRecycle();
        return ResponseEntity.ok(Result.success(null,
                purged == 0 ? "回收站为空，无需清空" : "回收站已清空，共清除 " + purged + " 个附件"));
    }

    private long sizeOf(Attachment attachment) {
        return attachment.getFileSize() == null ? 0L : attachment.getFileSize();
    }

    /**
     * 优先用库里记录的媒体类型，缺失或非法时退化为二进制流（保证总能下载）。
     */
    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 组装 Content-Disposition，同时给出兼容写法与 RFC 5987 写法，保证中文文件名在各浏览器都正常。
     *
     * @param type     {@code inline} 预览渲染，或 {@code attachment} 触发下载
     * @param filename 原始文件名
     */
    private String disposition(String type, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }
}
