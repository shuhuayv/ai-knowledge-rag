package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 组测试：soft-delete 应用语义 — 读接口 active-only（D5）。
 *
 * <p>纯 Mock 单元测试，不连真实 DB（REAL_DATABASE_WRITE_FROM_TESTS=NO）。</p>
 */
class KbDocumentSoftDeleteReadTest {

    private KbDocumentMapper mapper;
    private KbDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        mapper = mock(KbDocumentMapper.class);
        service = new KbDocumentServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private static KbDocument doc(Long id, Long isDeleted) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setIsDeleted(isDeleted);
        return d;
    }

    @Test
    void c1_listExcludesDeleted() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc(1L, 0L), doc(3L, 0L)));

        List<KbDocument> list = service.listDocuments();

        assertThat(list).extracting(KbDocument::getId).containsExactly(1L, 3L);
        // DB 层必须带 is_deleted = 0 条件（active-only）
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getExpression().getSqlSegment();
        assertThat(sql).containsIgnoringCase("is_deleted");
    }

    @Test
    void c2_pageExcludesDeleted() {
        Page<KbDocument> page = new Page<>(1, 10);
        page.setTotal(2);
        page.setRecords(List.of(doc(2L, 0L)));
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        IPage<KbDocument> result = service.pageDocuments(1, 10);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).extracting(KbDocument::getId).containsExactly(2L);
    }

    @Test
    void c3_getDeletedDocumentIsNotFound() {
        when(mapper.selectById(5L)).thenReturn(doc(5L, 5L)); // 已软删（is_deleted = 自身 id）

        assertThatThrownBy(() -> service.getDocumentById(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    @Test
    void c4_getMissingDocumentIsNotFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.getDocumentById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    @Test
    void c5_getActiveDocumentSucceeds() {
        KbDocument active = doc(2L, 0L);
        when(mapper.selectById(2L)).thenReturn(active);

        KbDocument result = service.getDocumentById(2L);

        assertThat(result.getId()).isEqualTo(2L);
    }

    @Test
    void h1_findActiveDocumentIdsBatchNoNPlus1() {
        KbDocument active1 = doc(1L, 0L);
        KbDocument active3 = doc(3L, 0L);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(active1, active3));

        Set<Long> result = service.findActiveDocumentIds(List.of(1L, 2L, 3L, 1L));

        assertThat(result).containsExactlyInAnyOrder(1L, 3L);
        // 单次批量查询（禁止 N+1）
        verify(mapper, org.mockito.Mockito.times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void h2_findActiveDocumentIdsEmptyOrNullReturnsEmptyWithoutQuery() {
        assertThat(service.findActiveDocumentIds(null)).isEmpty();
        assertThat(service.findActiveDocumentIds(List.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }
}
