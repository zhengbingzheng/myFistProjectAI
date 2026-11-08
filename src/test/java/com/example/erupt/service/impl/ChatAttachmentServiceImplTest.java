package com.example.erupt.service.impl;

import com.example.erupt.repository.mapper.ChatAttachmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 聊天附件与文件管理目录隔离的启动校验。
 *
 * <p>两个目录必须互不包含：运维脚本以附件根目录为界做备份/清理，
 * 任何一方的目录落在另一方内部都会导致误伤。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceImplTest {

    @Mock
    private ChatAttachmentMapper chatAttachmentMapper;

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("聊天附件目录位于文件管理目录内_启动失败")
    void chatInsideUploads_rejected() {
        String chatDir = tempDir.resolve("uploads").resolve("chat").toString();
        String fileDir = tempDir.resolve("uploads").toString();

        assertThatThrownBy(() -> new ChatAttachmentServiceImpl(chatAttachmentMapper, chatDir, fileDir))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("文件管理目录位于聊天附件目录内_同样启动失败")
    void uploadsInsideChat_rejected() {
        // 旧实现只查了 chat ⊂ uploads，这个方向会被放行
        String chatDir = tempDir.toString();
        String fileDir = tempDir.resolve("uploads").toString();

        assertThatThrownBy(() -> new ChatAttachmentServiceImpl(chatAttachmentMapper, chatDir, fileDir))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("两个目录平级_正常启动")
    void separateDirectories_allowed() {
        String chatDir = tempDir.resolve("chat-uploads").toString();
        String fileDir = tempDir.resolve("uploads").toString();

        assertThatCode(() -> new ChatAttachmentServiceImpl(chatAttachmentMapper, chatDir, fileDir))
                .doesNotThrowAnyException();
    }
}
