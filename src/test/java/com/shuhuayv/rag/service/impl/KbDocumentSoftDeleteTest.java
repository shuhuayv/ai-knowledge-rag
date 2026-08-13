package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.dedup.QdrantCleanupService;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E 组测试：deleteDocument 正式语义 — 物理删除 → soft delete（D7）。
 *
 * <p>纯 Mock 单元测试 + @TempDir 真实磁盘断言（证明物理文件默认不删）。
 * 不连真实 DB（REAL_DATABASE_WRITE_FROM_TESTS=NO）。</p>
 */
class KbDocumentSoftDeleteTest {

    @TempDir
    Path tempDir;

    private KbDocumentMapper mapper;
    private QdrantCleanupService qdrantCleanupService;
    private KbDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        mapper = mock(KbDocumentMapper.class);
        qdrantCleanupService = mock(QdrantCleanupService.class);
        service = new KbDocumentServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "qdrantCleanupService", qdrantCleanupService);
        // 默认 PHYSICAL_FILE_DELETE_ON_SOFT_DELETE=false
        ReflectionTestUtils.setField(service, "physicalFileDeleteOnSoftDelete", false);
    }

    private KbDocument activeDoc(Long id, Path filePath) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setIsDeleted(0L);
        d.setContentSha256("hash");
        d.setFilePath(filePath == null ? null : filePath.toString());
        return d;
    }

    @Test
    void e1_deleteActiveSoftDeletesRowAndKeepsPhysicalFile() throws Exception {
        Path file = Files.createFile(tempDir.resolve("doc.txt"));
        Files.writeString(file, "content");
        KbDocument doc = activeDoc(7L, file);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.deleteDocument(7L);

        // 1) DB row 不物理删：绝无 removeById / delete wrapper
        verify(mapper, never()).deleteById(any(Long.class));
        verify(mapper, never()).delete(any(Wrapper.class));
        // 2) 使用 UPDATE（soft delete）
        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
        // 3) Qdrant 补偿清理被调用
        verify(qdrantCleanupService).cleanupForDocument(7L);
        // 4) 物理文件默认不删（保留 rollback 依据）
        assertThat(file).exists();
    }

    @Test
    void e2_deleteSetsIsDeletedToSelfId() {
        KbDocument doc = activeDoc(7L, null);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.deleteDocument(7L);

        var captor = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KbDocument> update = captor.getValue();
        // SET 包含 is_deleted / canonical_document_id / dedup_batch 三列语义（软删只用 is_deleted）
        String sqlSet = update.getSqlSet();
        assertThat(sqlSet).containsIgnoringCase("is_deleted");
        // WHERE 守卫：id = ? AND is_deleted = 0
        String where = update.getExpression().getSqlSegment();
        assertThat(where).containsIgnoringCase("is_deleted");
        // 参数值包含自身 id 与 ACTIVE_FLAG(0)
        Map<String, Object> params = update.getParamNameValuePairs();
        assertThat(params.values()).contains(7L, 0L);
    }

    @Test
    void e3_softDeleteDoesNotModifyContentSha256() {
        KbDocument doc = activeDoc(7L, null);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.deleteDocument(7L);

        var captor = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        // SET 子句中绝不含 content_sha256（D7：不改 hash）
        assertThat(captor.getValue().getSqlSet()).doesNotContainIgnoringCase("content_sha256");
    }

    @Test
    void e4_repeatDeleteIsNotFoundPerContract() {
        KbDocument doc = activeDoc(7L, null);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.deleteDocument(7L); // 第一次成功

        // 第二次：行已软删（is_deleted = 自身 id）→ not found
        doc.setIsDeleted(7L);
        assertThatThrownBy(() -> service.deleteDocument(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    @Test
    void e5_deleteMissingDocumentIsNotFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteDocument(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
        verify(mapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void e6_optimisticGuardFailureThrows() {
        KbDocument doc = activeDoc(7L, null);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.deleteDocument(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("软删失败");
        verify(qdrantCleanupService, never()).cleanupForDocument(any());
    }

    @Test
    void e7_qdrantCleanupFailureDoesNotRollbackSoftDelete() {
        KbDocument doc = activeDoc(7L, null);
        when(mapper.selectById(7L)).thenReturn(doc);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("qdrant down"))
                .when(qdrantCleanupService).cleanupForDocument(7L);

        // 不抛异常：DB tombstone 是事实源，Qdrant 失败仅 warn，可单独重跑清理
        service.deleteDocument(7L);
        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
