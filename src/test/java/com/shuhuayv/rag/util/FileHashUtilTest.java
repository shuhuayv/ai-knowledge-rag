package com.shuhuayv.rag.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FileHashUtil 纯单元测试（无 Spring / 无 DB / 无真实网络）。
 *
 * <p>覆盖规格 HASH-01 ~ HASH-09：输出格式、确定性、空文件、filename 无关、bytes 相关、BOM 差异、
 * 路径无关、null / 不存在文件 / 目录的 fail-fast。</p>
 */
class FileHashUtilTest {

    @TempDir
    Path tempDir;

    /** SHA-256 空输入的标准结果，用于 HASH-03 对齐。 */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void hash01_outputIs64CharLowercaseHexAndMatchesIndependentComputation() throws Exception {
        Path f = tempDir.resolve("a.txt");
        Files.writeString(f, "hello");
        String h = FileHashUtil.sha256(f);

        assertThat(h).hasSize(64);
        assertThat(h).matches("^[0-9a-f]{64}$");
        // 与独立复算（不使用本工具）一致，确保语义正确
        assertThat(h).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("hello".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void hash02_deterministicForSameBytes() throws Exception {
        Path f1 = tempDir.resolve("x.bin");
        Path f2 = tempDir.resolve("y.bin");
        byte[] bytes = "RAG 内容身份".getBytes(StandardCharsets.UTF_8);
        Files.write(f1, bytes);
        Files.write(f2, bytes);
        assertThat(FileHashUtil.sha256(f1)).isEqualTo(FileHashUtil.sha256(f2));
    }

    /**
     * HASH-03：中文 filename 可正常 hash，无编码异常，且 filename 不参与 hash。
     * 同一字节内容，中文文件名（中文文档.txt）与 ASCII 文件名（doc.txt）→ 相同 hash，
     * 与 HASH-04「filename 无关」语义一致。
     */
    @Test
    void hash03_chineseFilenameHashesSameAsAsciiFilename() throws Exception {
        Path chineseName = tempDir.resolve("中文文档.txt");
        Path asciiName = tempDir.resolve("doc.txt");
        byte[] bytes = "知识库内容-RAG".getBytes(StandardCharsets.UTF_8);
        Files.write(chineseName, bytes);
        Files.write(asciiName, bytes);

        String hashChinese = FileHashUtil.sha256(chineseName);
        String hashAscii = FileHashUtil.sha256(asciiName);

        // 中文文件名不抛编码异常，输出仍为 64 位小写 hex
        assertThat(hashChinese).matches("^[0-9a-f]{64}$");
        // 同 bytes + 不同 filename（中文 vs ASCII）→ 相同 hash（filename 不进 hash）
        assertThat(hashChinese).isEqualTo(hashAscii);
    }

    @Test
    void hash03_emptyFileHasKnownHash() throws Exception {
        Path f = tempDir.resolve("empty.bin");
        Files.createFile(f);
        assertThat(FileHashUtil.sha256(f)).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void hash04_sameBytesDifferentFilenameSameHash() throws Exception {
        Path f1 = tempDir.resolve("report-FINAL.pdf");
        Path f2 = tempDir.resolve("report-v2.pdf");
        byte[] bytes = "identical content".getBytes(StandardCharsets.UTF_8);
        Files.write(f1, bytes);
        Files.write(f2, bytes);
        assertThat(FileHashUtil.sha256(f1)).isEqualTo(FileHashUtil.sha256(f2));
    }

    @Test
    void hash05_differentBytesSameFilenameDifferentHash() throws Exception {
        Path f1 = tempDir.resolve("doc1.txt");
        Path f2 = tempDir.resolve("doc2.txt");
        Files.writeString(f1, "version one");
        Files.writeString(f2, "version two");
        assertThat(FileHashUtil.sha256(f1)).isNotEqualTo(FileHashUtil.sha256(f2));
    }

    @Test
    void hash06_bomVsNoBomDiffer() throws Exception {
        Path without = tempDir.resolve("n.txt");
        Path withBom = tempDir.resolve("b.txt");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] base = "内容".getBytes(StandardCharsets.UTF_8);
        Files.write(without, base);

        byte[] with = new byte[bom.length + base.length];
        System.arraycopy(bom, 0, with, 0, bom.length);
        System.arraycopy(base, 0, with, bom.length, base.length);
        Files.write(withBom, with);

        assertThat(FileHashUtil.sha256(without)).isNotEqualTo(FileHashUtil.sha256(withBom));
    }

    @Test
    void hash07_hashIndependentOfAbsolutePath() throws Exception {
        Path dirA = tempDir.resolve("a");
        Path dirB = tempDir.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        byte[] bytes = "same".getBytes(StandardCharsets.UTF_8);
        Path fa = dirA.resolve("f.bin");
        Path fb = dirB.resolve("f.bin");
        Files.write(fa, bytes);
        Files.write(fb, bytes);
        assertThat(FileHashUtil.sha256(fa)).isEqualTo(FileHashUtil.sha256(fb));
    }

    @Test
    void hash08_nullPathThrows() {
        assertThatThrownBy(() -> FileHashUtil.sha256(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path must not be null");
    }

    @Test
    void hash09_missingFileOrDirectoryThrows() throws Exception {
        Path missing = tempDir.resolve("nope.bin");
        assertThatThrownBy(() -> FileHashUtil.sha256(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file not found");

        Path dir = tempDir.resolve("sub");
        Files.createDirectories(dir);
        assertThatThrownBy(() -> FileHashUtil.sha256(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is a directory");
    }
}
