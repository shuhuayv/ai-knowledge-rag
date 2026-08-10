package com.shuhuayv.rag.vector.model;

import java.util.List;
import java.util.Map;

/**
 * Qdrant {@code POST /collections/{c}/points/scroll} 的<b>单页</b>结果。
 *
 * <p>本轮（PR-1）只提供<b>单页契约</b>：调用方拿到 {@link #nextOffset()} 后自行决定是否继续翻页。
 * 有意<b>不</b>在此实现自动全量遍历与 maxPages 防护——那属于未来 ConsistencyCheckService 的职责，
 * 放在这里会让一个纯数据传输对象承担循环控制与熔断语义。</p>
 *
 * <p><b>Point ID 契约范围（重要）</b>：本项目仅保证<b>UUID 字符串形式</b>的 Point ID 可用。
 * Qdrant 原生还支持无符号整数 Point ID，但<b>不在本轮契约范围内</b>——
 * {@code scrollPoints} 不假设 {@link #nextOffset()} 必然是数值，也不做数值化 offset 回溯。
 * 因此本组件<b>不构成</b>一个完整的通用 Qdrant SDK，仅服务于本项目既有的 UUID Point ID 场景。</p>
 *
 * @param points     本页点列表；scroll 固定 {@code with_payload=true} / {@code with_vector=false}，
 *                   因此每个点只含 id 与 payload，不含向量
 * @param nextOffset 下一页起始 offset（对应 Qdrant 的 {@code result.next_page_offset}，本项目为 UUID 字符串形式）；
 *                   {@code null} 表示已是最后一页
 */
public record ScrollPage(List<ScrollPoint> points, String nextOffset) {

    /**
     * scroll 返回的单个点。
     *
     * @param id      点 ID。<b>当前项目仅保证 UUID 字符串形式</b>可用；无符号整数形式的 Qdrant Point ID
     *               不在本轮契约范围内（scroll 不做数值化 offset 回溯）
     * @param payload 点的 payload；缺失时为空 Map，永不为 {@code null}
     */
    public record ScrollPoint(String id, Map<String, Object> payload) {
    }

    /**
     * 构造空页（无点、无下一页）。
     *
     * @return 空的单页结果
     */
    public static ScrollPage empty() {
        return new ScrollPage(List.of(), null);
    }

    /**
     * 是否还有下一页。
     *
     * @return {@code nextOffset} 非空且非空白时返回 {@code true}
     */
    public boolean hasNext() {
        return nextOffset != null && !nextOffset.isBlank();
    }

    /**
     * 本页点数量。
     *
     * @return 点数量
     */
    public int size() {
        return points == null ? 0 : points.size();
    }
}
