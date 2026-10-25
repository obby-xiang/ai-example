package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * 样例知识库首启播种器：知识目录不存在/为空时，把打包进 classpath 的 {@code kb-samples/}
 * 复制到 {@code app.ai.kb.dir}，让样例随包带入、运行时零配置可用。
 *
 * <h2>何时播种</h2>
 * <ul>
 * <li>目录不存在；</li>
 * <li>目录存在且为空（含"上次播种中断"留下的空目录）。</li>
 * </ul>
 * 目录已有内容则<b>一律跳过</b>（哪怕缺 manifest.json）——宁可不播，也不与既有语料混用；
 * http 源根本走不到这里（无本地目录）。
 *
 * <h2>字节口径</h2>
 * manifest 的 {@code sha} 按 LF 计算，而仓库工作区在 Windows 上是 CRLF。播种统一按
 * {@link MarkdownChunker#normalize(String)} 落成 LF 再写，保证"文件实算 sha == manifest 声明"，
 * 刷新链路（{@code LocalDirKbSource} 实算 / {@code KbService} 规范化后比对）零 WARN。
 *
 * <h2>失败姿态</h2>
 * 任何异常只 WARN，不影响启动：知识库仍按目录现状加载（缺 manifest 时 fail-open 到"加载中"）。
 * 播种先写同目录下的临时 staging 再整体换入，失败不会留下"半份语料 + manifest"。
 *
 * <h2>多实例（同一台机 / 同一挂载卷）</h2>
 * 知识目录是<b>文件系统共享</b>的，"目录为空"判据在三个实例同时启动时同样会全为真
 * （这与数据库种子的先查后写是同一类竞态）。故播种前在父目录上取一把<b>跨进程文件锁</b>
 * （{@code .kb-samples-seed.lock} 的 {@link java.nio.channels.FileLock}）：
 * <ul>
 * <li>抢到锁的实例<b>重新判空</b>后再播（另一实例可能刚播完并释放）；</li>
 * <li>抢不到锁（另一实例正在播）⇒ 直接跳过，不等待 —— 知识库是 fail-open 面，
 * 目录最终会被持锁方填满，跳过方按目录现状加载即可；</li>
 * <li>锁文件<b>不删除</b>（删掉会让等待方各自打开新 inode，等于锁失效），它是隐藏名、
 * 不在 {@code *.md} 语料命名空间内，不会被知识源扫描。</li>
 * </ul>
 * 同一 JVM 内另一个线程持锁时 {@code tryLock} 抛 {@link java.nio.channels.OverlappingFileLockException}，
 * 同样按"有人在播"处理。
 */
@Slf4j
public final class KbSampleSeeder {

    /** classpath 下的样例根（构建期由 maven-resources-plugin 从 docs/kb-samples 拷入）。 */
    static final String CLASSPATH_ROOT = "kb-samples";

    private static final String MANIFEST = "manifest.json";

    /** 跨实例播种锁（放在知识目录的父目录，与 staging 同级；不参与语料扫描）。 */
    private static final String LOCK_FILE = ".kb-samples-seed.lock";

    private final Path targetDir;

    private final ObjectMapper mapper;

    private final ClassLoader loader;

    public KbSampleSeeder(Path targetDir, ObjectMapper mapper) {
        this(targetDir, mapper, KbSampleSeeder.class.getClassLoader());
    }

    KbSampleSeeder(Path targetDir, ObjectMapper mapper, ClassLoader loader) {
        this.targetDir = targetDir.toAbsolutePath().normalize();
        this.mapper = mapper;
        this.loader = loader;
    }

    /**
     * 需要时播种样例。
     *
     * @return true = 本次真的播种了；false = 跳过/失败（失败已 WARN）
     */
    public boolean seedIfAbsent() {
        if (this.loader.getResource(CLASSPATH_ROOT + "/" + MANIFEST) == null) {
            // 非打包构建（IDE 直接跑测试等）：没有随包样例可播，静默跳过
            log.debug("classpath 缺少 {}/{}，跳过样例播种", CLASSPATH_ROOT, MANIFEST);
            return false;
        }
        if (!needsSeed()) {
            return false;
        }
        Path parent = this.targetDir.getParent();
        if (parent == null) {
            log.warn("知识库目录没有父目录，无法安全播种：{}", this.targetDir);
            return false;
        }
        try {
            Files.createDirectories(parent);
        }
        catch (IOException e) {
            log.warn("知识库目录创建失败（不影响启动，按目录现状加载）：{}：{}", parent, safeMessage(e));
            return false;
        }
        try (FileChannel channel = FileChannel.open(parent.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = tryLock(channel)) {
            if (lock == null) {
                log.info("另一实例正在播种知识库样例，本实例跳过：dir={}", this.targetDir);
                return false;
            }
            // 抢锁后重判：持锁方可能刚播完并释放
            if (!needsSeed()) {
                return false;
            }
            return doSeed(parent);
        }
        catch (IOException e) {
            log.warn("知识库样例播种锁不可用（不影响启动，按目录现状加载）：{}", safeMessage(e));
            return false;
        }
    }

    /** 非阻塞抢文件锁；同 JVM 已有线程持锁时按"别人在播"处理（而不是抛给调用方）。 */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        }
        catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private boolean doSeed(Path parent) {
        Path staging = null;
        try {
            String manifestText = MarkdownChunker.normalize(readText(MANIFEST));
            KbManifest manifest = KbManifest.parse(
                    manifestText.getBytes(StandardCharsets.UTF_8), this.mapper);
            manifest.validate();

            staging = Files.createTempDirectory(parent, ".kb-samples-seed-");
            for (KbDocMeta meta : manifest.docs()) {
                Path out = staging.resolve(meta.effectiveFile()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("样例文档路径越界：" + meta.effectiveFile());
                }
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                // 按 LF 落盘：与 manifest 的 sha 计算口径一致
                Files.write(out, MarkdownChunker.normalize(readText(meta.effectiveFile()))
                        .getBytes(StandardCharsets.UTF_8));
            }
            Files.write(staging.resolve(MANIFEST), manifestText.getBytes(StandardCharsets.UTF_8));
            moveIntoPlace(staging);
            log.info("知识库样例已随包播种：dir={} docs={}（来源 classpath:{}/）", this.targetDir,
                    manifest.docs().size(), CLASSPATH_ROOT);
            return true;
        }
        catch (Exception e) {
            if (staging != null) {
                deleteQuietly(staging);
            }
            log.warn("知识库样例自动播种失败（不影响启动，按目录现状加载）：{}", safeMessage(e));
            return false;
        }
    }

    private boolean needsSeed() {
        if (!Files.exists(this.targetDir)) {
            return true;
        }
        if (!Files.isDirectory(this.targetDir)) {
            log.warn("app.ai.kb.dir 不是目录，跳过样例播种：{}", this.targetDir);
            return false;
        }
        if (Files.isRegularFile(this.targetDir.resolve(MANIFEST))) {
            return false;
        }
        try (Stream<Path> children = Files.list(this.targetDir)) {
            if (children.findAny().isPresent()) {
                // 有内容但缺 manifest：视为用户/生产的既有语料，播种会混料
                log.warn("知识库目录非空但缺少 manifest.json，跳过样例播种（避免与既有内容混用）：{}",
                        this.targetDir);
                return false;
            }
        }
        catch (IOException e) {
            log.warn("检查知识库目录失败，跳过样例播种：{}：{}", this.targetDir, safeMessage(e));
            return false;
        }
        return true;
    }

    private void moveIntoPlace(Path staging) throws IOException {
        if (Files.exists(this.targetDir)) {
            // needsSeed 已挡掉非空目录，这里只可能是空目录
            Files.delete(this.targetDir);
        }
        try {
            Files.move(staging, this.targetDir, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, this.targetDir);
        }
    }

    private String readText(String name) throws IOException {
        URL url = this.loader.getResource(CLASSPATH_ROOT + "/" + name);
        if (url == null) {
            throw new IOException("classpath 缺少样例资源：" + CLASSPATH_ROOT + "/" + name);
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                }
                catch (IOException ignored) {
                    // 清理失败留待下轮；staging 目录名不参与知识源扫描
                }
            });
        }
        catch (IOException ignored) {
            // 同上
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
