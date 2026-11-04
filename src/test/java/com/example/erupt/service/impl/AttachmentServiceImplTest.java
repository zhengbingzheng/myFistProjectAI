package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.config.AttachmentProperties;
import com.example.erupt.model.dto.AttachmentQueryRequest;
import com.example.erupt.model.vo.AttachmentRecycleVO;
import com.example.erupt.model.vo.AttachmentVO;
import com.example.erupt.repository.entity.Attachment;
import com.example.erupt.repository.mapper.AttachmentMapper;
import com.example.erupt.service.AttachmentPreview;
import com.example.erupt.service.AttachmentResource;
import com.example.erupt.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceImplTest {

    private static final byte[] CONTENT = "hello attachment".getBytes(StandardCharsets.UTF_8);

    @Mock
    private AttachmentMapper attachmentMapper;

    @TempDir
    private Path tempDir;

    private AttachmentProperties properties;
    private AttachmentServiceImpl attachmentService;

    @BeforeEach
    void setUp() {
        properties = new AttachmentProperties();
        properties.setStorageDir(tempDir.toString());
        properties.setMaxUploadSizeKb(1024);
        properties.setZipMaxTotalSizeKb(1024);
        attachmentService = new AttachmentServiceImpl(attachmentMapper, properties,
                new TransactionTemplate(Mockito.mock(PlatformTransactionManager.class)));
        stubWriteDefaults();
    }

    /**
     * 写操作的默认返回：按「入参条数」给出受影响行数，与真实 SQL 行为一致。
     *
     * <p>用 {@code lenient()} 而不是 {@code when()}：Mockito 扩展默认开启严格 stub，
     * 在公共初始化里用 when 打桩后，凡是用不到这些调用的用例都会报
     * UnnecessaryStubbingException。需要模拟失败的用例再用 when 覆盖即可。</p>
     */
    @SuppressWarnings("unchecked")
    private void stubWriteDefaults() {
        lenient().when(attachmentMapper.insert(any(Attachment.class))).thenReturn(1);
        lenient().when(attachmentMapper.incrementDownloadCount(any())).thenReturn(1);
        lenient().when(attachmentMapper.restoreByIds(anyList(), any(LocalDateTime.class)))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        lenient().when(attachmentMapper.moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        lenient().when(attachmentMapper.deleteRecycledByIds(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }

    @Test
    @DisplayName("upload_validFile_savesFileAndReturnsVO")
    void upload_validFile_savesFileAndReturnsVO() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "季度报告.pdf", "application/pdf", CONTENT);

        AttachmentVO vo = attachmentService.upload(file);

        assertThat(vo.id()).hasSize(32);
        assertThat(vo.originalName()).isEqualTo("季度报告.pdf");
        assertThat(vo.fileExt()).isEqualTo("pdf");
        assertThat(vo.fileType()).isEqualTo("document");
        assertThat(vo.fileSize()).isEqualTo(CONTENT.length);
        assertThat(vo.downloadCount()).isZero();

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).insert(captor.capture());
        Attachment saved = captor.getValue();
        assertThat(saved.getStoragePath()).endsWith(".pdf");
        assertThat(saved.getDelFlag()).isEqualTo("0");
        assertThat(saved.getFileHash()).hasSize(64);
        // 文件必须真的落盘，否则后续下载无从谈起
        assertThat(Files.exists(tempDir.resolve(saved.getStoragePath()))).isTrue();
    }

    @Test
    @DisplayName("upload_emptyFile_throwsBadRequest")
    void upload_emptyFile_throwsBadRequest() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> attachmentService.upload(empty))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(attachmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("upload_exceedsMaxUploadSize_throwsBadRequest")
    void upload_exceedsMaxUploadSize_throwsBadRequest() {
        properties.setMaxUploadSizeKb(1); // 1 KB
        MockMultipartFile tooBig = new MockMultipartFile(
                "file", "big.bin", "application/octet-stream", new byte[2048]);

        assertThatThrownBy(() -> attachmentService.upload(tooBig))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(attachmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("upload_insertFails_removesStoredFile")
    void upload_insertFails_removesStoredFile() throws IOException {
        when(attachmentMapper.insert(any(Attachment.class)))
                .thenThrow(new DuplicateKeyException("duplicate id"));
        MockMultipartFile file = new MockMultipartFile("file", "孤儿.txt", "text/plain", CONTENT);

        assertThatThrownBy(() -> attachmentService.upload(file))
                .isInstanceOf(Exception.class);

        // 入库失败后磁盘上不能留下孤儿文件，否则会无限占用空间且没有记录可寻
        long fileCount = Files.walk(tempDir).filter(Files::isRegularFile).count();
        assertThat(fileCount).isZero();
    }

    @Test
    @DisplayName("upload_originalNameContainsPath_onlyKeepsFileName")
    void upload_originalNameContainsPath_onlyKeepsFileName() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd", "text/plain", CONTENT);

        AttachmentVO vo = attachmentService.upload(file);

        // 原始文件名里的目录部分必须被抹掉，避免路径穿越与展示混乱
        assertThat(vo.originalName()).isEqualTo("passwd");
    }

    @Test
    @DisplayName("uploadBatch_multipleFiles_returnsAllAndInsertsEach")
    void uploadBatch_multipleFiles_returnsAllAndInsertsEach() {
        List<MultipartFile> files = List.of(
                new MockMultipartFile("files", "a.txt", "text/plain", CONTENT),
                new MockMultipartFile("files", "b.png", "image/png", CONTENT));

        List<AttachmentVO> uploaded = attachmentService.uploadBatch(files);

        assertThat(uploaded).hasSize(2);
        assertThat(uploaded.get(0).fileType()).isEqualTo("document");
        assertThat(uploaded.get(1).fileType()).isEqualTo("image");
        verify(attachmentMapper, org.mockito.Mockito.times(2)).insert(any(Attachment.class));
    }

    @Test
    @DisplayName("uploadBatch_emptyFiles_throwsBadRequest")
    void uploadBatch_emptyFiles_throwsBadRequest() {
        assertThatThrownBy(() -> attachmentService.uploadBatch(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("getResource_attachmentNotExists_throwsNotFound")
    void getResource_attachmentNotExists_throwsNotFound() {
        when(attachmentMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> attachmentService.getResource("missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getResource_fileMissingOnDisk_throwsNotFound")
    void getResource_fileMissingOnDisk_throwsNotFound() throws IOException {
        Attachment attachment = mockAttachment("id-1", "失踪.txt", 10L);
        attachment.setStoragePath("2026/10/not-exists.bin");
        when(attachmentMapper.selectById("id-1")).thenReturn(attachment);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.getResource("id-1"), BusinessException.class);

        assertThat(ex.getMessage()).contains("已丢失");
    }

    @Test
    @DisplayName("getResourcesForZip_exceedsTotalSize_throwsBadRequestWithHint")
    void getResourcesForZip_exceedsTotalSize_throwsBadRequestWithHint() throws IOException {
        // 阈值 1024 KB，两个文件加起来 1200 KB
        Attachment first = mockAttachment("id-1", "a.txt", 700 * 1024L);
        Attachment second = mockAttachment("id-2", "b.txt", 500 * 1024L);
        when(attachmentMapper.selectByIds(List.of("id-1", "id-2")))
                .thenReturn(List.of(first, second));

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.getResourcesForZip(List.of("id-1", "id-2")),
                BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo(AttachmentService.ZIP_TOO_LARGE_MESSAGE);
    }

    @Test
    @DisplayName("getResourcesForZip_withinTotalSize_returnsResources")
    void getResourcesForZip_withinTotalSize_returnsResources() throws IOException {
        Attachment first = mockAttachment("id-1", "a.txt", 300L);
        when(attachmentMapper.selectByIds(List.of("id-1"))).thenReturn(List.of(first));

        List<AttachmentResource> resources = attachmentService.getResourcesForZip(List.of("id-1"));

        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).attachment().getOriginalName()).isEqualTo("a.txt");
        assertThat(Files.isRegularFile(resources.get(0).path())).isTrue();
    }

    @Test
    @DisplayName("getResourcesForZip_containsMissingId_throwsNotFound")
    void getResourcesForZip_containsMissingId_throwsNotFound() {
        when(attachmentMapper.selectByIds(List.of("id-1", "ghost")))
                .thenReturn(List.of(mockAttachmentWithoutFile("id-1", "a.txt", 10L)));

        assertThatThrownBy(() -> attachmentService.getResourcesForZip(List.of("id-1", "ghost")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getResourcesForZip_duplicateIds_deduplicatesBeforeQuery")
    void getResourcesForZip_duplicateIds_deduplicatesBeforeQuery() throws IOException {
        when(attachmentMapper.selectByIds(List.of("id-1")))
                .thenReturn(List.of(mockAttachment("id-1", "a.txt", 10L)));

        attachmentService.getResourcesForZip(List.of("id-1", "id-1"));

        verify(attachmentMapper).selectByIds(List.of("id-1"));
    }

    @Test
    @DisplayName("getResourcesForZip_emptyIds_throwsBadRequest")
    void getResourcesForZip_emptyIds_throwsBadRequest() {
        assertThatThrownBy(() -> attachmentService.getResourcesForZip(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("getResourcesForZip_pathTraversal_throwsBadRequest")
    void getResourcesForZip_pathTraversal_throwsBadRequest() {
        Attachment malicious = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        malicious.setStoragePath("../../outside.bin");
        when(attachmentMapper.selectByIds(anyList())).thenReturn(List.of(malicious));

        assertThatThrownBy(() -> attachmentService.getResourcesForZip(List.of("id-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("streamDownload_writesContentAndIncrementsCount")
    void streamDownload_writesContentAndIncrementsCount() throws IOException {
        Attachment attachment = mockAttachment("id-1", "a.txt", 10L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        attachmentService.streamDownload(new AttachmentResource(attachment, tempDir.resolve(attachment.getStoragePath())), out);

        assertThat(out.toByteArray()).isEqualTo(CONTENT);
        verify(attachmentMapper).incrementDownloadCount("id-1");
    }

    @Test
    @DisplayName("streamZip_packagesAllFilesWithContent")
    void streamZip_packagesAllFilesWithContent() throws IOException {
        Attachment first = mockAttachment("id-1", "a.txt", 10L);
        Attachment second = mockAttachment("id-2", "b.txt", 10L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        attachmentService.streamZip(List.of(
                new AttachmentResource(first, tempDir.resolve(first.getStoragePath())),
                new AttachmentResource(second, tempDir.resolve(second.getStoragePath()))), out);

        List<String> entryNames = new java.util.ArrayList<>();
        java.util.List<String> entryContents = new java.util.ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                entryContents.add(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
                zin.closeEntry();
            }
        }
        assertThat(entryNames).containsExactly("a.txt", "b.txt");
        assertThat(entryContents).containsExactly("hello attachment", "hello attachment");
    }

    @Test
    @DisplayName("streamZip_duplicateOriginalNames_appendsSequence")
    void streamZip_duplicateOriginalNames_appendsSequence() throws IOException {
        Attachment first = mockAttachment("id-1", "same.txt", 10L);
        Attachment second = mockAttachment("id-2", "same.txt", 10L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        attachmentService.streamZip(List.of(
                new AttachmentResource(first, tempDir.resolve(first.getStoragePath())),
                new AttachmentResource(second, tempDir.resolve(second.getStoragePath()))), out);

        List<String> entryNames = new java.util.ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                zin.closeEntry();
            }
        }
        // 同名条目必须改名，否则解压时后者会覆盖前者
        assertThat(entryNames).containsExactly("same.txt", "same(1).txt");
    }

    @Test
    @DisplayName("listNormal_noParams_usesDefaultPaging")
    void listNormal_noParams_usesDefaultPaging() {
        when(attachmentMapper.countByCondition(null, null, null, null, "0")).thenReturn(1L);
        when(attachmentMapper.selectPageByCondition(null, null, null, null, "0", 0L, 10))
                .thenReturn(List.of(mockAttachmentWithoutFile("id-1", "a.txt", 10L)));

        PageResult<AttachmentVO> result = attachmentService.listNormal(
                new AttachmentQueryRequest(null, null, null, null, null, null));

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).originalName()).isEqualTo("a.txt");
    }

    @Test
    @DisplayName("listNormal_invalidPagingParams_correctedInsteadOfRejected")
    void listNormal_invalidPagingParams_correctedInsteadOfRejected() {
        when(attachmentMapper.countByCondition(null, null, null, null, "0")).thenReturn(1L);
        // pageSize 传 999 应收敛到上限 100
        when(attachmentMapper.selectPageByCondition(null, null, null, null, "0", 0L, 100))
                .thenReturn(List.of(mockAttachmentWithoutFile("id-1", "a.txt", 10L)));

        PageResult<AttachmentVO> result = attachmentService.listNormal(
                new AttachmentQueryRequest(null, null, null, null, 0, 999));

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("listNormal_pageBeyondTotal_returnsEmptyItemsWithRealTotal")
    void listNormal_pageBeyondTotal_returnsEmptyItemsWithRealTotal() {
        // 只有 3 条却请求第 91 页：这不算错误，返回空页并带上真实总数，前端据此跳回合理页
        when(attachmentMapper.countByCondition(null, null, null, null, "0")).thenReturn(3L);
        when(attachmentMapper.selectPageByCondition(null, null, null, null, "0", 900L, 10))
                .thenReturn(List.of());

        PageResult<AttachmentVO> result = attachmentService.listNormal(
                new AttachmentQueryRequest(null, null, null, null, 91, 10));

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(3L);
        assertThat(result.page()).isEqualTo(91);
    }

    @Test
    @DisplayName("listNormal_offsetExceedsLimit_throwsBadRequest")
    void listNormal_offsetExceedsLimit_throwsBadRequest() {
        // 偏移量超过上限属于昂贵查询，必须拦下来（区别于上面"页码越界但偏移量不大"的情况）
        when(attachmentMapper.countByCondition(null, null, null, null, "0")).thenReturn(1L);

        assertThatThrownBy(() -> attachmentService.listNormal(
                new AttachmentQueryRequest(null, null, null, null, 60000, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        // verify 里要么全用匹配器、要么全用原始值，混用会被 Mockito 判定为用法错误
        verify(attachmentMapper, never()).selectPageByCondition(isNull(), isNull(), isNull(), isNull(),
                eq("0"), anyLong(), anyInt());
    }

    @Test
    @DisplayName("listNormal_startTimeAfterEndTime_throwsBadRequest")
    void listNormal_startTimeAfterEndTime_throwsBadRequest() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        assertThatThrownBy(() -> attachmentService.listNormal(
                new AttachmentQueryRequest(null, null, start, end, 1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("listNormal_invalidFileType_throwsBadRequest")
    void listNormal_invalidFileType_throwsBadRequest() {
        BusinessException ex = catchThrowableOfType(() -> attachmentService.listNormal(
                new AttachmentQueryRequest(null, "unknownType", null, null, 1, 10)), BusinessException.class);

        assertThat(ex.getMessage()).contains("非法的文件分类").contains("image/document/video/audio/archive/other");
    }

    @Test
    @DisplayName("listNormal_keywordEscapesLikeWildcards")
    void listNormal_keywordEscapesLikeWildcards() {
        when(attachmentMapper.countByCondition("100/%/_报告", null, null, null, "0")).thenReturn(0L);

        attachmentService.listNormal(new AttachmentQueryRequest("100%_报告", null, null, null, 1, 10));

        // 通配符必须被转义，否则 "%" 会退化成全表匹配
        verify(attachmentMapper).countByCondition("100/%/_报告", null, null, null, "0");
    }

    @Test
    @DisplayName("listNormal_blankKeywordNormalizedToNull")
    void listNormal_blankKeywordNormalizedToNull() {
        when(attachmentMapper.countByCondition(null, null, null, null, "0")).thenReturn(0L);

        attachmentService.listNormal(new AttachmentQueryRequest("   ", "  ", null, null, 1, 10));

        verify(attachmentMapper).countByCondition(null, null, null, null, "0");
    }

    @Test
    @DisplayName("listRecycled_queriesDelFlagOneAndReturnsRecycleVO")
    void listRecycled_queriesDelFlagOneAndReturnsRecycleVO() {
        Attachment deleted = mockAttachmentWithoutFile("id-1", "已删.txt", 10L);
        deleted.setDelFlag("1");
        deleted.setDeleteTime(LocalDateTime.of(2026, 10, 1, 12, 0));
        deleted.setRecycleExpireTime(LocalDateTime.of(2026, 11, 1, 12, 0));
        when(attachmentMapper.countByCondition(null, null, null, null, "1")).thenReturn(1L);
        when(attachmentMapper.selectPageByCondition(null, null, null, null, "1", 0L, 10))
                .thenReturn(List.of(deleted));

        PageResult<AttachmentRecycleVO> result = attachmentService.listRecycled(
                new AttachmentQueryRequest(null, null, null, null, null, null));

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        // 回收站 VO 必须带出删除时间与到期时间，否则界面无法提示用户何时会被清理
        assertThat(result.items().get(0).deleteTime()).isEqualTo(LocalDateTime.of(2026, 10, 1, 12, 0));
        assertThat(result.items().get(0).recycleExpireTime()).isEqualTo(LocalDateTime.of(2026, 11, 1, 12, 0));
    }

    @Test
    @DisplayName("restore_existingInRecycle_executesUpdate")
    void restore_existingInRecycle_executesUpdate() {
        Attachment deleted = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(deleted);

        attachmentService.restore("id-1");

        verify(attachmentMapper).restoreByIds(eq(List.of("id-1")), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restore_notExists_throwsNotFound")
    void restore_notExists_throwsNotFound() {
        when(attachmentMapper.selectByIdAnyFlag("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.restore("ghost"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        verify(attachmentMapper, never()).restoreByIds(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restore_notInRecycle_throwsConflict")
    void restore_notInRecycle_throwsConflict() {
        Attachment normal = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        normal.setDelFlag("0");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(normal);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.restore("id-1"), BusinessException.class);

        // 记录存在但状态不对 → 409，且不能静默当成成功
        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).restoreByIds(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restore_blankId_throwsBadRequest")
    void restore_blankId_throwsBadRequest() {
        assertThatThrownBy(() -> attachmentService.restore("  "))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("restoreBatch_allInRecycle_executesOnce")
    void restoreBatch_allInRecycle_executesOnce() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1", "id-2"))).thenReturn(2L);

        attachmentService.restoreBatch(List.of("id-1", "id-2"));

        verify(attachmentMapper).restoreByIds(eq(List.of("id-1", "id-2")), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restoreBatch_containsMissingId_throwsNotFoundAndSkipsUpdate")
    void restoreBatch_containsMissingId_throwsNotFoundAndSkipsUpdate() {
        when(attachmentMapper.countByIds(List.of("id-1", "ghost"))).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.restoreBatch(List.of("id-1", "ghost")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        // 整批拒绝：一条都不能被恢复
        verify(attachmentMapper, never()).restoreByIds(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restoreBatch_containsNonRecycled_throwsConflictAndSkipsUpdate")
    void restoreBatch_containsNonRecycled_throwsConflictAndSkipsUpdate() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1", "id-2"))).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.restoreBatch(List.of("id-1", "id-2")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).restoreByIds(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restoreBatch_emptyIds_throwsBadRequest")
    void restoreBatch_emptyIds_throwsBadRequest() {
        assertThatThrownBy(() -> attachmentService.restoreBatch(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("restoreBatch_duplicateIds_deduplicates")
    void restoreBatch_duplicateIds_deduplicates() {
        when(attachmentMapper.countByIds(List.of("id-1"))).thenReturn(1L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1"))).thenReturn(1L);

        attachmentService.restoreBatch(List.of("id-1", "id-1"));

        verify(attachmentMapper).restoreByIds(eq(List.of("id-1")), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("restoreBatch_exceedsLimit_throwsBadRequest")
    void restoreBatch_exceedsLimit_throwsBadRequest() {
        List<String> tooMany = IntStream.rangeClosed(1, 501).mapToObj(i -> "id-" + i).toList();

        assertThatThrownBy(() -> attachmentService.restoreBatch(tooMany))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("getPreview_image_isInlineWithOriginalType")
    void getPreview_image_isInlineWithOriginalType() throws IOException {
        Attachment image = mockAttachment("id-1", "pic.png", 19L);
        image.setContentType("image/png");
        when(attachmentMapper.selectById("id-1")).thenReturn(image);

        AttachmentPreview preview = attachmentService.getPreview("id-1");

        assertThat(preview.inline()).isTrue();
        assertThat(preview.contentType()).isEqualTo("image/png");
        assertThat(preview.sandbox()).isFalse();
    }

    @Test
    @DisplayName("getPreview_pdf_isInline")
    void getPreview_pdf_isInline() throws IOException {
        Attachment pdf = mockAttachment("id-1", "doc.pdf", 2048L);
        pdf.setContentType("application/pdf");
        when(attachmentMapper.selectById("id-1")).thenReturn(pdf);

        AttachmentPreview preview = attachmentService.getPreview("id-1");

        // PDF 必须能 inline，否则浏览器的内置阅读器会被当成下载
        assertThat(preview.inline()).isTrue();
        assertThat(preview.contentType()).isEqualTo("application/pdf");
        assertThat(preview.size()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("getPreview_html_degradesToPlainTextAndSandboxed")
    void getPreview_html_degradesToPlainTextAndSandboxed() throws IOException {
        Attachment html = mockAttachment("id-1", "report.html", 100L);
        html.setContentType("text/html");
        when(attachmentMapper.selectById("id-1")).thenReturn(html);

        AttachmentPreview preview = attachmentService.getPreview("id-1");

        // HTML 若原样内联，用户访问预览地址就会在同域下执行上传者写的脚本。
        // 降级为纯文本后可以正常预览内容（源码），但不具备执行能力。
        assertThat(preview.inline()).isTrue();
        assertThat(preview.contentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(preview.sandbox()).isTrue();
    }

    @Test
    @DisplayName("getPreview_svgAndArchive_neverInline")
    void getPreview_svgAndArchive_neverInline() throws IOException {
        Attachment svg = mockAttachment("id-1", "icon.svg", 100L);
        svg.setContentType("image/svg+xml");
        when(attachmentMapper.selectById("id-1")).thenReturn(svg);

        AttachmentPreview svgPreview = attachmentService.getPreview("id-1");

        // SVG 是 XML，可携带 <script>，顶层访问即可执行，必须回到下载
        assertThat(svgPreview.inline()).isFalse();
        assertThat(svgPreview.contentType()).isEqualTo("application/octet-stream");

        Attachment zip = mockAttachment("id-2", "bundle.zip", 100L);
        zip.setContentType("application/zip");
        when(attachmentMapper.selectById("id-2")).thenReturn(zip);

        AttachmentPreview zipPreview = attachmentService.getPreview("id-2");

        // 浏览器渲染不了的类型降级为下载，前端不必再为此分支
        assertThat(zipPreview.inline()).isFalse();
        assertThat(zipPreview.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("getPreview_missingContentType_fallsBackToDownload")
    void getPreview_missingContentType_fallsBackToDownload() throws IOException {
        Attachment unknown = mockAttachment("id-1", "unknown.bin", 10L);
        unknown.setContentType(null);
        when(attachmentMapper.selectById("id-1")).thenReturn(unknown);

        AttachmentPreview preview = attachmentService.getPreview("id-1");

        // 类型不明时按最保守处理：不内联，避免被浏览器嗅探成可执行文档
        assertThat(preview.inline()).isFalse();
        assertThat(preview.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("getPreview_json_isInlineAsPlainText")
    void getPreview_json_isInlineAsPlainText() throws IOException {
        Attachment json = mockAttachment("id-1", "data.json", 10L);
        json.setContentType("application/json");
        when(attachmentMapper.selectById("id-1")).thenReturn(json);

        AttachmentPreview preview = attachmentService.getPreview("id-1");

        assertThat(preview.inline()).isTrue();
        assertThat(preview.contentType()).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    @DisplayName("getPreview_notFound_throwsNotFound")
    void getPreview_notFound_throwsNotFound() {
        when(attachmentMapper.selectById("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.getPreview("ghost"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getPreview_recycledAttachment_throwsNotFound")
    void getPreview_recycledAttachment_throwsNotFound() {
        // 回收站里的附件不能预览：selectById 只查 del_flag='0'
        Attachment deleted = mockAttachmentWithoutFile("id-1", "pic.png", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectById("id-1")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.getPreview("id-1"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("getPreview_fileMissingOnDisk_throwsNotFound")
    void getPreview_fileMissingOnDisk_throwsNotFound() {
        Attachment broken = mockAttachmentWithoutFile("id-1", "pic.png", 10L);
        broken.setContentType("image/png");
        when(attachmentMapper.selectById("id-1")).thenReturn(broken);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.getPreview("id-1"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("streamPreview_writesContentWithoutCountingDownload")
    void streamPreview_writesContentWithoutCountingDownload() throws IOException {
        Attachment image = mockAttachment("id-1", "pic.png", 19L);
        image.setContentType("image/png");
        when(attachmentMapper.selectById("id-1")).thenReturn(image);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AttachmentPreview preview = attachmentService.getPreview("id-1");
        attachmentService.streamPreview(preview, out);

        assertThat(out.toByteArray()).isEqualTo(CONTENT);
        // 预览不能计入下载次数：反复打开预览会让 download_count 失去统计意义
        verify(attachmentMapper, never()).incrementDownloadCount("id-1");
    }

    @Test
    @DisplayName("moveToRecycle_normalAttachment_executesUpdate")
    void moveToRecycle_normalAttachment_executesUpdate() {
        Attachment normal = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        normal.setDelFlag("0");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(normal);

        attachmentService.moveToRecycle("id-1");

        verify(attachmentMapper).moveToRecycleByIds(eq(List.of("id-1")),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycle_notExists_throwsNotFound")
    void moveToRecycle_notExists_throwsNotFound() {
        when(attachmentMapper.selectByIdAnyFlag("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycle("ghost"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        verify(attachmentMapper, never()).moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycle_alreadyInRecycle_throwsConflict")
    void moveToRecycle_alreadyInRecycle_throwsConflict() {
        Attachment deleted = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(deleted);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycle("id-1"), BusinessException.class);

        // 重复删除若静默成功，用户会以为发生了别的动作，必须显式指出
        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycle_blankId_throwsBadRequest")
    void moveToRecycle_blankId_throwsBadRequest() {
        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycle("  "), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("moveToRecycleBatch_allNormal_executesOnce")
    void moveToRecycleBatch_allNormal_executesOnce() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countNormalByIds(List.of("id-1", "id-2"))).thenReturn(2L);

        attachmentService.moveToRecycleBatch(List.of("id-1", "id-2"));

        verify(attachmentMapper).moveToRecycleByIds(eq(List.of("id-1", "id-2")),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycleBatch_containsMissingId_throwsNotFoundAndSkips")
    void moveToRecycleBatch_containsMissingId_throwsNotFoundAndSkips() {
        when(attachmentMapper.countByIds(List.of("id-1", "ghost"))).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycleBatch(List.of("id-1", "ghost")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        verify(attachmentMapper, never()).moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycleBatch_containsRecycled_throwsConflictAndSkips")
    void moveToRecycleBatch_containsRecycled_throwsConflictAndSkips() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countNormalByIds(List.of("id-1", "id-2"))).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycleBatch(List.of("id-1", "id-2")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("moveToRecycleBatch_emptyIds_throwsBadRequest")
    void moveToRecycleBatch_emptyIds_throwsBadRequest() {
        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycleBatch(List.of()), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("moveToRecycleBatch_exceedsLimit_throwsBadRequest")
    void moveToRecycleBatch_exceedsLimit_throwsBadRequest() {
        List<String> tooMany = IntStream.rangeClosed(1, 501).mapToObj(i -> "id-" + i).toList();

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycleBatch(tooMany), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("purge_recycledAttachment_deletesRecordAndFile")
    void purge_recycledAttachment_deletesRecordAndFile() throws IOException {
        Attachment deleted = mockAttachment("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(deleted);
        Path file = tempDir.resolve(deleted.getStoragePath());

        assertThat(Files.exists(file)).isTrue();

        attachmentService.purge("id-1");

        verify(attachmentMapper).deleteRecycledByIds(List.of("id-1"));
        // 彻底删除必须连磁盘文件一起清掉，只删记录会留下没人认领的垃圾文件
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("purge_normalAttachment_throwsConflictAndKeepsFile")
    void purge_normalAttachment_throwsConflictAndKeepsFile() throws IOException {
        Attachment normal = mockAttachment("id-1", "a.txt", 10L);
        normal.setDelFlag("0");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(normal);
        Path file = tempDir.resolve(normal.getStoragePath());

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purge("id-1"), BusinessException.class);

        // 正常状态的附件不允许被彻底删除：必须先移入回收站，这是防误删的唯一防线
        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).deleteRecycledByIds(anyList());
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("purge_notExists_throwsNotFound")
    void purge_notExists_throwsNotFound() {
        when(attachmentMapper.selectByIdAnyFlag("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purge("ghost"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        verify(attachmentMapper, never()).deleteRecycledByIds(anyList());
    }

    @Test
    @DisplayName("purgeBatch_allInRecycle_deletesRecordsAndFiles")
    void purgeBatch_allInRecycle_deletesRecordsAndFiles() throws IOException {
        Attachment first = mockAttachment("id-1", "a.txt", 10L);
        first.setDelFlag("1");
        Attachment second = mockAttachment("id-2", "b.txt", 10L);
        second.setDelFlag("1");
        Path firstFile = tempDir.resolve(first.getStoragePath());
        Path secondFile = tempDir.resolve(second.getStoragePath());
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.selectRecycledByIds(List.of("id-1", "id-2")))
                .thenReturn(List.of(first, second));

        attachmentService.purgeBatch(List.of("id-1", "id-2"));

        verify(attachmentMapper).deleteRecycledByIds(List.of("id-1", "id-2"));
        assertThat(Files.exists(firstFile)).isFalse();
        assertThat(Files.exists(secondFile)).isFalse();
    }

    @Test
    @DisplayName("purgeBatch_containsNormal_throwsConflictAndSkips")
    void purgeBatch_containsNormal_throwsConflictAndSkips() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1", "id-2"))).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purgeBatch(List.of("id-1", "id-2")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(attachmentMapper, never()).deleteRecycledByIds(anyList());
    }

    @Test
    @DisplayName("purgeBatch_emptyIds_throwsBadRequest")
    void purgeBatch_emptyIds_throwsBadRequest() {
        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purgeBatch(List.of()), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("emptyRecycle_emptyBin_skipsDelete")
    void emptyRecycle_emptyBin_skipsDelete() {
        when(attachmentMapper.selectRecycledBatch(500)).thenReturn(List.of());

        attachmentService.emptyRecycle();

        // 回收站为空不算错误，但也不该发出一条没有意义的 DELETE
        verify(attachmentMapper, never()).deleteRecycledByIds(anyList());
    }

    @Test
    @DisplayName("emptyRecycle_withItems_deletesRecordsAndFiles")
    void emptyRecycle_withItems_deletesRecordsAndFiles() throws IOException {
        Attachment deleted = mockAttachment("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectRecycledBatch(500)).thenReturn(List.of(deleted), List.of());
        Path file = tempDir.resolve(deleted.getStoragePath());

        attachmentService.emptyRecycle();

        verify(attachmentMapper).deleteRecycledByIds(List.of("id-1"));
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("upload_insertReturnsZero_throwsInternalErrorAndRemovesFile")
    void upload_insertReturnsZero_throwsInternalErrorAndRemovesFile() throws IOException {
        when(attachmentMapper.insert(any(Attachment.class))).thenReturn(0);
        MockMultipartFile file = new MockMultipartFile("file", "未入库.txt", "text/plain", CONTENT);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.upload(file), BusinessException.class);

        // insert 返回 0 表示记录没写进去。若按成功返回，前端拿到的 id 在库里根本不存在
        assertThat(ex.resultCode()).isEqualTo(ResultCode.INTERNAL_ERROR);
        try (var files = Files.walk(tempDir)) {
            // 已落盘的文件必须清理掉，不能留下没有记录对应的孤儿文件
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
    }

    @Test
    @DisplayName("restore_affectedRowsZero_throwsInternalError")
    void restore_affectedRowsZero_throwsInternalError() {
        Attachment deleted = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(deleted);
        when(attachmentMapper.restoreByIds(anyList(), any(LocalDateTime.class))).thenReturn(0);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.restore("id-1"), BusinessException.class);

        // 状态校验通过但数据库并未改动（多半是被并发改过），必须判定失败而非静默成功
        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("moveToRecycle_affectedRowsZero_throwsInternalError")
    void moveToRecycle_affectedRowsZero_throwsInternalError() {
        Attachment normal = mockAttachmentWithoutFile("id-1", "a.txt", 10L);
        normal.setDelFlag("0");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(normal);
        when(attachmentMapper.moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(0);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycle("id-1"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("moveToRecycleBatch_partialAffected_throwsInternalError")
    void moveToRecycleBatch_partialAffected_throwsInternalError() {
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countNormalByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        // 只影响 1 条：并发下可能发生，属于「部分成功」，必须整批失败而不是只认成功的那部分
        when(attachmentMapper.moveToRecycleByIds(anyList(), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(1);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.moveToRecycleBatch(List.of("id-1", "id-2")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("purge_affectedRowsZero_throwsInternalErrorAndKeepsFile")
    void purge_affectedRowsZero_throwsInternalErrorAndKeepsFile() throws IOException {
        Attachment deleted = mockAttachment("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectByIdAnyFlag("id-1")).thenReturn(deleted);
        when(attachmentMapper.deleteRecycledByIds(anyList())).thenReturn(0);
        Path file = tempDir.resolve(deleted.getStoragePath());

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purge("id-1"), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
        // 最关键的断言：记录没删掉就绝不能删文件。
        // 否则会留下「记录还在、文件没了」的不一致，既查不出也自愈不了。
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("purgeBatch_partialAffected_throwsInternalError")
    void purgeBatch_partialAffected_throwsInternalError() throws IOException {
        Attachment first = mockAttachment("id-1", "a.txt", 10L);
        first.setDelFlag("1");
        Attachment second = mockAttachment("id-2", "b.txt", 10L);
        second.setDelFlag("1");
        when(attachmentMapper.countByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.countInRecycleByIds(List.of("id-1", "id-2"))).thenReturn(2L);
        when(attachmentMapper.selectRecycledByIds(List.of("id-1", "id-2")))
                .thenReturn(List.of(first, second));
        when(attachmentMapper.deleteRecycledByIds(anyList())).thenReturn(1);

        BusinessException ex = catchThrowableOfType(
                () -> attachmentService.purgeBatch(List.of("id-1", "id-2")), BusinessException.class);

        assertThat(ex.resultCode()).isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("emptyRecycle_returnsActualAffectedCount")
    void emptyRecycle_returnsActualAffectedCount() throws IOException {
        Attachment deleted = mockAttachment("id-1", "a.txt", 10L);
        deleted.setDelFlag("1");
        when(attachmentMapper.selectRecycledBatch(500)).thenReturn(List.of(deleted), List.of());

        int affected = attachmentService.emptyRecycle();

        // 回执必须是数据库实际影响行数，而不是查出来的条数
        assertThat(affected).isEqualTo(1);
    }

    /** 构造一条附件记录，并在磁盘上放好对应的真实文件。 */
    private Attachment mockAttachment(String id, String originalName, long fileSize) throws IOException {
        Attachment attachment = mockAttachmentWithoutFile(id, originalName, fileSize);
        Path target = tempDir.resolve(attachment.getStoragePath());
        Files.createDirectories(target.getParent());
        Files.write(target, CONTENT);
        return attachment;
    }

    /** 构造附件记录但不落盘文件（用于验证「文件已丢失」等分支）。 */
    private Attachment mockAttachmentWithoutFile(String id, String originalName, long fileSize) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setOriginalName(originalName);
        attachment.setFileSize(fileSize);
        attachment.setFileExt("txt");
        attachment.setContentType("text/plain");
        attachment.setStoragePath("2026/10/" + id + ".txt");
        return attachment;
    }
}
