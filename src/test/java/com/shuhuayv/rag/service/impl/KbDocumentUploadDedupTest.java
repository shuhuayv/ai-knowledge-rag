package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.service.DocumentUploadResult;
import com.shuhuayv.rag.util.FileHashUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KbDocumentServiceImpl.uploadDocument + findPreferredActiveDuplicate 纯单元测试（UPLOAD-01 ~ UPLOAD-13）。
 *
 * <p>通过 {@link ReflectionTestUtils#setField} 注入 mock 的 {@code baseMapper} 与本地 uploadDir，
 * 不启动 Spring、不连真实 DB。LambdaQueryWrapper 需要 MyBatis-Plus 的 TableInfo 缓存，
 * 因此在 {@code @BeforeEach} 中显式初始化（与探测结论一致：缺失缓存会抛
 * "can not find lambda cache for this entity"）。</p>
 */
class KbDocumentUploadDedupTest {

    @TempDir
    Path tempDir;

    private KbDocumentMapper mapper;
    private KbDocumentServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        // 初始化 KbDocument 的 TableInfo 缓存，使 LambdaQueryWrapper 能解析列名。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);

        mapper = mock(KbDocumentMapper.class);
        service = new KbDocumentServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());

        // insert 模拟自增 id 回填（MyBatis-Plus 真实行为）。
        // 注意：重新 stub insert（如 upload12/13 的 thenThrow）时，Mockito 会用 matcher 的 null 实参
        // 触发一次既有 stub 求值，因此这里必须对 null 参数做防御，避免 NPE。
        when(mapper.insert((KbDocument) any())).thenAnswer(inv -> {
            KbDocument d = inv.getArgument(0);
            if (d != null && d.getId() == null) {
                d.setId(idSeq.getAndIncrement());
            }
            return 1;
        });
    }

    private static MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    // ---------------- 基础上传语义 ----------------

    @Test
    void upload01_firstUploadIsNotDuplicateAndPersists() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        DocumentUploadResult result = service.uploadDocument(txt("a.txt", "alpha"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.document().getContentSha256()).matches("^[0-9a-f]{64}$");
        assertThat(result.document().getIsDeleted()).isEqualTo(0L);
        verify(mapper, times(1)).insert((KbDocument) any());
        verify(mapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void upload02_duplicateUploadReturnsExistingWithoutNewRow() throws Exception {
        String content = "dup-content";
        Path probe = tempDir.resolve("probe.txt");
        Files.writeString(probe, content);
        String hash = FileHashUtil.sha256(probe);

        KbDocument existing = new KbDocument();
        existing.setId(2L);
        existing.setStatus("UPLOADED");
        existing.setContentSha256(hash);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        DocumentUploadResult result = service.uploadDocument(txt("b.txt", content));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.document().getId()).isEqualTo(2L);
        // 命中重复：绝不再写入新 DB 行。
        verify(mapper, never()).insert((KbDocument) any());
    }

    /**
     * UPLOAD-05：duplicate 后本次新落盘文件必须已删除（磁盘不残留孤儿 UUID 副本）。
     * 使用独立 uploads 子目录，duplicate 分支触发 cleanupUploadedFile 后，
     * 断言 uploads/ 目录中不再有任何文件（无新增 UUID 文件残留）。
     */
    @Test
    void upload05_duplicateUploadDeletesNewlyWrittenFile() throws Exception {
        // 独立 uploads 子目录作为 uploadDir，便于精确断言「目录内无新增文件」。
        Path uploadDir = Files.createDirectory(tempDir.resolve("uploads"));
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());

        String content = "dup-cleanup";
        Path probe = tempDir.resolve("probe.txt");
        Files.writeString(probe, content);
        String hash = FileHashUtil.sha256(probe);

        KbDocument existing = new KbDocument();
        existing.setId(2L);
        existing.setStatus("UPLOADED");
        existing.setContentSha256(hash);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        DocumentUploadResult result = service.uploadDocument(txt("b.txt", content));

        assertThat(result.duplicate()).isTrue();
        // 关键断言：本次新落盘文件已被删除，uploads/ 目录为空（不会每次新增 UUID 副本）。
        try (Stream<Path> paths = Files.list(uploadDir)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void upload03_differentContentIsNewDocument() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        service.uploadDocument(txt("a.txt", "first"));
        DocumentUploadResult second = service.uploadDocument(txt("c.txt", "second"));

        assertThat(second.duplicate()).isFalse();
        verify(mapper, times(2)).insert((KbDocument) any());
    }

    // ---------------- 校验失败（旧行为完全保留）----------------

    @Test
    void upload04_emptyFileRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "e.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.uploadDocument(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不能为空");
        verify(mapper, never()).insert((KbDocument) any());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void upload05_oversizedFileRejected() {
        MultipartFile big = new OversizedMultipartFile("big.pdf", 50L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> service.uploadDocument(big))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件大小不能超过 50MB");
        verify(mapper, never()).insert((KbDocument) any());
    }

    @Test
    void upload06_unsupportedTypeRejected() {
        MockMultipartFile docx = new MockMultipartFile("file", "x.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "data".getBytes());
        assertThatThrownBy(() -> service.uploadDocument(docx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 TXT 和 PDF 文件格式");
        verify(mapper, never()).insert((KbDocument) any());
    }

    // ---------------- hash 失败：禁止写入 DB 行 ----------------

    @Test
    void upload07_hashFailureDoesNotWriteRowAndThrows() {
        MultipartFile broken = new HashFailingMultipartFile("a.txt", "content");
        assertThatThrownBy(() -> service.uploadDocument(broken))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("内容哈希计算失败");
        // hash 失败绝不允许插入 DB 行（新数据必须保证 contentSha256 != NULL）。
        verify(mapper, never()).insert((KbDocument) any());
    }

    // ---------------- findPreferredActiveDuplicate 选择语义 ----------------

    @Test
    void upload08_unknownHashReturnsNull() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        assertThat(service.findPreferredActiveDuplicate("deadbeef")).isNull();
    }

    @Test
    void upload09_prefersIndexedOverFailedAndUploaded() {
        // company_policy 场景：doc1 FAILED / doc2 INDEXED / doc4 UPLOADED 共享同一 hash。
        KbDocument failed = doc(1L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument indexed = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 2, 0, 0));
        KbDocument uploaded = doc(4L, "UPLOADED", LocalDateTime.of(2026, 1, 3, 0, 0));
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(failed, indexed, uploaded));

        KbDocument winner = service.findPreferredActiveDuplicate("same-hash");
        assertThat(winner).isSameAs(indexed);
    }

    @Test
    void upload10_tieBreakByCreatedAtAsc() {
        KbDocument earlier = doc(10L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument later = doc(11L, "INDEXED", LocalDateTime.of(2026, 2, 1, 0, 0));
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(later, earlier));

        assertThat(service.findPreferredActiveDuplicate("h")).isSameAs(earlier);
    }

    @Test
    void upload11_nullCreatedAtSortedLastByIdAsc() {
        KbDocument withDate = doc(3L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument withoutDate = doc(5L, "INDEXED", null);
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(withoutDate, withDate));

        // createdAt 为 null 的行排在有值行之后；二者都有值时再比 id。
        assertThat(service.findPreferredActiveDuplicate("h")).isSameAs(withDate);
    }

    // ---------------- 并发 race fallback ----------------

    @Test
    void upload12_duplicateKeyRaceReturnsWinner() {
        KbDocument winner = doc(7L, "UPLOADED", LocalDateTime.now());
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList(), List.of(winner));
        when(mapper.insert((KbDocument) any())).thenThrow(new DuplicateKeyException("dup"));

        DocumentUploadResult result = service.uploadDocument(txt("r.txt", "race"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.document().getId()).isEqualTo(7L);
        verify(mapper, times(1)).insert((KbDocument) any());
    }

    @Test
    void upload13_duplicateKeyRaceRethrowsWhenNoWinner() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList(), Collections.emptyList());
        when(mapper.insert((KbDocument) any())).thenThrow(new DuplicateKeyException("dup"));

        // 重查仍为空 → 不能把任意 DuplicateKeyException 当内容重复吞掉，必须原样上抛。
        assertThatThrownBy(() -> service.uploadDocument(txt("r2.txt", "race2")))
                .isInstanceOf(DuplicateKeyException.class);
        verify(mapper, times(1)).insert((KbDocument) any());
    }

    private static KbDocument doc(Long id, String status, LocalDateTime createdAt) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setContentSha256("shared-hash");
        return d;
    }

    /** getSize 超过上限但不实际占用内存的 MultipartFile（校验阶段即失败，transferTo 不会被调用）。 */
    static class OversizedMultipartFile implements MultipartFile {
        private final String name;
        private final long size;

        OversizedMultipartFile(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public String getOriginalFilename() {
            return name;
        }

        public String getContentType() {
            return "application/pdf";
        }

        public boolean isEmpty() {
            return false;
        }

        public long getSize() {
            return size;
        }

        public byte[] getBytes() {
            return new byte[0];
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public void transferTo(File dest) {
            // 不会被调用（大小校验先于落盘）。
        }

        public void transferTo(Path dest) {
            // 不会被调用。
        }
    }

    /** transferTo 故意不落盘（落盘后立即删除），使后续 FileHashUtil.sha256 抛 "file not found"。 */
    static class HashFailingMultipartFile implements MultipartFile {
        private final String name;
        private final byte[] content;

        HashFailingMultipartFile(String name, String content) {
            this.name = name;
            this.content = content.getBytes();
        }

        public String getName() {
            return name;
        }

        public String getOriginalFilename() {
            return name;
        }

        public String getContentType() {
            return "text/plain";
        }

        public boolean isEmpty() {
            return content.length == 0;
        }

        public long getSize() {
            return content.length;
        }

        public byte[] getBytes() {
            return content;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        public void transferTo(File dest) throws IOException {
            Files.deleteIfExists(dest.toPath());
        }

        public void transferTo(Path dest) throws IOException {
            Files.deleteIfExists(dest);
        }
    }
}
