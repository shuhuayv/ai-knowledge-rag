package com.shuhuayv.rag.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件内容哈希工具（PR-2A Document Identity 的唯一 hash 入口）。
 *
 * <p><b>语义（不可改变）</b>：{@code content_sha256 = SHA-256(落盘文件的 RAW FILE BYTES)}。
 * 哈希输入<b>只有文件原始字节</b>：</p>
 * <ul>
 *   <li>绝<b>不</b>对内容做 normalize（不 trim、不去 BOM、不做 PDF 文本抽取、不拼接 chunk）；</li>
 *   <li>文件路径、目录、存储文件名（{@code UUID_原名}）、原始文件名<b>一律不参与</b>哈希计算。</li>
 * </ul>
 *
 * <p><b>由此推导的行为契约</b>（已被单元测试 HASH-04 ~ HASH-07 固化）：</p>
 * <ul>
 *   <li>相同 bytes + 不同 filename → <b>相同</b> hash；</li>
 *   <li>相同 filename + 不同 bytes → <b>不同</b> hash；</li>
 *   <li>带 BOM 与不带 BOM → raw bytes 不同 → <b>不同</b> hash（有意为之，非缺陷）；</li>
 *   <li>hash 与文件所在 absolute path 无关。</li>
 * </ul>
 *
 * <p><b>唯一入口约束</b>：文档上传去重（{@code KbDocumentServiceImpl}）与历史数据回填
 * （{@code ContentHashBackfillRunner}）<b>必须</b>共用本方法。禁止在任何地方另写第二套哈希实现，
 * 否则两条链路可能产生不一致的 content identity。</p>
 *
 * <p><b>与 Qdrant Point ID 无关</b>：本哈希是 {@code kb_document} 的内容级身份，
 * 不是向量库 Point ID。Point ID 方案保持 {@code UUID v3(documentId:chunkId:indexVersion)} 不变。</p>
 *
 * <p>实现要点：使用 8KB 缓冲区流式读取，50MB 上限的文件也不会整体载入内存。</p>
 */
public final class FileHashUtil {

    /** 哈希算法：SHA-256（Java 平台强制实现，必定可用）。 */
    private static final String ALGORITHM = "SHA-256";

    /** 流式读取缓冲区大小（8KB），避免大文件整体入内存。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private FileHashUtil() {
        throw new AssertionError("FileHashUtil 为工具类，禁止实例化");
    }

    /**
     * 计算文件原始字节的 SHA-256，返回小写十六进制字符串（固定 64 字符）。
     *
     * @param path 目标文件路径，必须存在且为普通文件
     * @return lowercase hex 编码的 SHA-256，长度恒为 64，匹配 {@code ^[0-9a-f]{64}$}
     * @throws IllegalArgumentException {@code path} 为 null（fail fast）、文件不存在、或路径指向目录
     * @throws UncheckedIOException     读取过程中发生 IO 错误（原始异常作为 cause 保留，绝不吞掉）
     * @throws IllegalStateException    当前 JVM 不支持 SHA-256（理论上不会发生）
     */
    public static String sha256(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("file not found: " + path);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("path is a directory, not a file: " + path);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("current JVM does not support " + ALGORITHM, e);
        }

        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            // IO 错误绝不静默吞掉：包装为非受检异常并保留 cause，供上层诊断。
            throw new UncheckedIOException("failed to hash file: " + path, e);
        }

        // HexFormat.of() 天然输出小写十六进制；SHA-256 输出 32 字节 → 恒为 64 字符。
        return HexFormat.of().formatHex(digest.digest());
    }
}
