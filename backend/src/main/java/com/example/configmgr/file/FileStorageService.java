package com.example.configmgr.file;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件存储：<b>数据库只存相对存储键</b>，实际路径每次由当前实例的 {@code app.file.storage-path} 解析。
 *
 * <h2>为什么键与路径必须拆开</h2>
 * 旧实现把 {@code target.toAbsolutePath()} 直接写进 {@code task_files.storage_path}。于是
 * <ul>
 * <li>多实例（共享盘/同一挂载点）下 A 实例写本机绝对路径、B 实例按同一行读 → 读到的是
 * B 机器上不存在的路径；</li>
 * <li>即使单实例，只要重启时 JVM 工作目录变了（换 shell 目录、换服务单元、容器内 CWD 不同），
 * 库里所有历史 {@code storage_path} 立刻失效；</li>
 * <li>库里存本机绝对路径本身也是信息泄露面（错误消息会带出目录结构）。</li>
 * </ul>
 * 现口径：{@link #store}/{@link #allocate} 返回 {@link StoredFile}（{@code key} 是 UUID + 扩展名，
 * 相对基目录），调用方把 {@code key} 落库、把 {@code path} 交给写盘 API（如
 * {@code ExcelWriter#write}）；读取/摘要/删除一律经 {@link #resolve(String)} 还原成本实例路径。
 *
 * <h2>拒绝面</h2>
 * <ul>
 * <li><b>绝对路径一律拒绝</b>：库里只有相对存储键，绝对路径只能来自错误调用（或错误数据）——
 * {@code resolve} 见到绝对路径即抛 {@link IllegalArgumentException}，绝不按本机路径直读；</li>
 * <li><b>相对键拒绝上跳段</b>：相对键只要含 {@code ..} 段即抛 {@link IllegalArgumentException}，
 * 解析结果也必须落在基目录之内 —— 存储键不接受任何调用方拼接的路径；</li>
 * <li>{@link #store}/{@link #allocate} 从不接受外部键，键由本服务分配。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** 流式摘要的读缓冲（顺序读，不整文件进内存）。 */
    private static final int DIGEST_BUFFER_BYTES = 8192;

    /** 新键扩展名的形状：一个点 + 1~10 个 ASCII 字母数字（挡掉 `./../` 这类拼接）。 */
    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,10}");

    private final AppProperties appProperties;

    /**
     * 存储基目录（绝对、已规范化，必要时创建）。
     *
     * <p>返回绝对路径：相对键的解析结果必须能与基目录做前缀比较，相对形式在不同 CWD 下没有可比性。
     */
    public Path baseDir() {
        Path dir = Path.of(appProperties.getFile().getStoragePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件存储目录: " + dir, e);
        }
        return dir;
    }

    /** 分配一个新键（不建文件）：调用方拿 {@link StoredFile#path()} 写盘，把 key 落库。 */
    public StoredFile allocate(String ext) {
        String key = UUID.randomUUID() + safeExtension(ext);
        return new StoredFile(key, keyPath(key));
    }

    /**
     * 写一件文件并返回 {@code key + path}。
     *
     * <p>失败（含读流中途 IOException）时清掉半截文件，不给存储目录留孤儿。
     */
    public StoredFile store(InputStream in, String filename) throws IOException {
        StoredFile stored = allocate(extensionOf(filename));
        try {
            Files.copy(in, stored.path(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException | RuntimeException e) {
            deleteQuietly(stored.path());
            throw e;
        }
        log.debug("Stored file {} -> key={} path={}", filename, stored.key(), stored.path());
        return stored;
    }

    /**
     * 把库里的相对存储键解析成本实例可用的路径。
     *
     * @param key 相对存储键（新口径；绝对路径不接受）
     * @throws IllegalArgumentException 空值、绝对路径、非法路径、相对键含 {@code ..}、或解析结果越出基目录
     */
    public Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("文件存储键为空");
        }
        Path raw;
        try {
            raw = Path.of(key);
        }
        catch (InvalidPathException e) {
            throw new IllegalArgumentException("文件存储键非法: " + key, e);
        }
        if (raw.isAbsolute()) {
            throw new IllegalArgumentException("文件存储键不得为绝对路径: " + key);
        }
        if (hasParentSegment(raw)) {
            throw new IllegalArgumentException("文件存储键不得包含上跳段 '..': " + key);
        }
        Path base = baseDir();
        Path resolved = base.resolve(raw).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("文件存储键越出存储目录: " + key);
        }
        return resolved;
    }

    public byte[] read(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    /**
     * 文件摘要（T3-4）：<b>流式</b> sha256 + 字节数，一次顺序读。
     *
     * <h2>为什么必须流式（S3-5 的施工口径）</h2>
     * {@link #read(String)} 是 {@code Files.readAllBytes}（全量进内存）。预检查/导入的指纹计算
     * 若复用它，百 MB 级上传件会<b>再</b>额外占用一份等量堆内存（文件已被 {@code excelReader} 读过一次）。
     * 本方法用 {@link DigestInputStream} + 8KB 缓冲顺序读，只经手缓冲块，不持有整文件。
     *
     * @return {@code sha256}（小写十六进制）+ {@code size}（真实字节数，与摘要同一次读得出）
     */
    public FileDigest digest(String key) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new CodedIllegalStateException(ErrorCodes.SHA256_UNAVAILABLE, "运行环境缺少 SHA-256 实现", ex);
        }
        long size = 0L;
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        try (InputStream raw = Files.newInputStream(resolve(key));
                DigestInputStream in = new DigestInputStream(raw, md)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
            }
        }
        return new FileDigest(HexFormat.of().formatHex(md.digest()), size);
    }

    /** 文件摘要值对象（sha256 小写十六进制 + 字节数）。 */
    public record FileDigest(String sha256, long size) {
    }

    /**
     * 把字节写到 {@link #allocate}/{@link #store} 给出的路径。
     *
     * <p>拒绝基目录之外的路径：写盘路径只应来自本服务的分配结果，越界即调用方接错了参数。
     */
    public void write(Path path, byte[] data) throws IOException {
        Path target = requireUnderBase(path);
        Files.createDirectories(target.getParent());
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        }
        catch (IOException e) {
            log.warn("Failed to delete file {}: {}", key, e.getMessage());
        }
    }

    /**
     * 事务<b>提交成功后</b>删除（覆盖写换路径后回收旧件）。
     *
     * <p>提交前不删的原因：新行可能因 FK/唯一约束/提交期回滚而放弃落库，此时旧件仍是
     * {@code task_files.storage_path} 的当前引用，先删就把"可回退"变成"彻底丢文件"。
     *
     * <h2>使用边界</h2>
     * <ul>
     * <li>只在"旧键即将失去引用"时调用一次（上传/导出保存/导出作业覆盖写）；</li>
     * <li>必须在同一事务内调用（提交/回滚才有意义）；无同步可挂时立即删除 —— 此时没有回滚可言；</li>
     * <li>回调在<b>提交线程</b>上执行，异常不外抛（见 {@link #deleteInCallback}）：删旧件失败只留
     * WARN，不能连累同批其它同步器（{@code JobTerminalWriter} 的终态落写就在同一批里）；</li>
     * <li><b>不重试</b>：没有扫描器（本批不做保留期/孤儿清理），失败件等下次覆盖或任务删除时再清。</li>
     * </ul>
     */
    public void deleteAfterCommit(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteInCallback(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteInCallback(key);
            }
        });
    }

    /**
     * 事务<b>回滚后</b>删除本次新写入的文件（提交成功则保留）。
     *
     * <p>覆盖两类回滚：{@code save} 抛异常，以及方法正常返回后提交期才失败/被标记 rollback-only
     * （例如外层事务回滚）。调用方只需在"<b>写盘之前或紧随写盘</b>"登记一次，不必自己分辨哪一种；
     * 写盘前登记可让写盘中途 IOException 留下的半截文件也被回收。
     *
     * <h2>使用边界</h2>
     * <ul>
     * <li>只登记"本次事务新产生、尚未被任何已提交行引用"的键（否则回滚会删掉仍在用的件）；</li>
     * <li>必须在同一事务内登记；无同步可挂时不删（没有回滚，文件就是本次提交的产物）；</li>
     * <li>回调异常不外抛（同 {@link #deleteAfterCommit}）：{@code afterCompletion} 链上还有其它
     * 同步器（回滚收敛等），删盘失败只能 WARN；</li>
     * <li><b>不重试</b>：与 {@link #deleteAfterCommit} 同一口径。</li>
     * </ul>
     */
    public void deleteAfterRollback(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.debug("无活动事务同步，跳过回滚清理登记: {}", key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteInCallback(key);
                }
            }
        });
    }

    /**
     * 事务回调里的删除：<b>任何运行时异常都就地兜住</b>并 WARN。
     *
     * <p>为什么不是直接 {@link #delete(String)}：{@code delete} 只兜 {@link IOException}，
     * 而 {@code Path.of} 对非法路径抛 {@code InvalidPathException}、{@link #resolve} 对越界键抛
     * {@link IllegalArgumentException}、受管环境下还可能抛 {@code SecurityException} —— 这些从
     * {@code afterCommit} 抛出会中断 Spring 的同步器遍历，让排在其后的终态落写/回滚收敛静默不执行。
     */
    private void deleteInCallback(String key) {
        try {
            delete(key);
        }
        catch (RuntimeException e) {
            log.warn("事务回调删除文件失败（已忽略，不阻断后续同步器） key={}: {}", key, e.toString());
        }
    }

    private Path keyPath(String key) {
        Path base = baseDir();
        Path resolved = base.resolve(key).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalStateException("生成的存储键越出存储目录: " + key);
        }
        return resolved;
    }

    private Path requireUnderBase(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("存储路径为空");
        }
        Path base = baseDir();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("写盘路径越出存储目录: " + path);
        }
        return target;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException | RuntimeException e) {
            log.warn("落盘失败后清理半截文件失败 path={}: {}", path, e.toString());
        }
    }

    /** 相对键里出现 {@code ..} 段即拒绝（先于 normalize：{@code a/../b} 正常，但仍是不可接受的键形状）。 */
    private static boolean hasParentSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    /** 只接受 ASCII 字母数字扩展名；挡掉客户端文件名里夹带的路径片段。 */
    private static String safeExtension(String ext) {
        if (ext == null || ext.isEmpty()) {
            return "";
        }
        String candidate = ext.startsWith(".") ? ext : "." + ext;
        if (!SAFE_EXTENSION.matcher(candidate).matches()) {
            throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED,
                    "文件扩展名不合法：" + ext);
        }
        return candidate.toLowerCase(Locale.ROOT);
    }
}
