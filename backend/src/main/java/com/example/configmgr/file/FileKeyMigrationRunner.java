package com.example.configmgr.file;

import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 历史 {@code task_files.storage_path} 绝对路径 → 相对存储键的<b>显式迁移</b>。
 *
 * <h2>为什么需要（且不是自动启动）</h2>
 * 新口径只在库里存 UUID 相对键，但存量行仍是旧实现写死的本机绝对路径。迁移把它们复制到当前
 * {@code app.file.storage-path} 下并以新键改写行；旧行在新实例（共享存储）上才能被解析。
 * 这是<b>运维动作</b>：会写盘、会改库，故默认关闭，只有显式配置
 * {@code app.file.key-migration.enabled=true} 才在启动时执行一次（见
 * {@code AppProperties.FileStorage.KeyMigration}）。不配该键时本类根本不装配，绝不自动迁移。
 *
 * <h2>多实例单飞</h2>
 * 两个实例同时迁移同一行会各自分配一个 UUID、各复制一份文件，后落库者覆盖前者引用的键 —— 前者那份
 * 成为孤儿，且"复制不移动"让磁盘上多出两份。故 {@link #migrate()} 必须先经
 * {@link FileKeyMigrationLock} 抢跨实例锁（生产 = Redis {@code SET NX PX}）：抢不到就<b>整轮放弃</b>
 * （幂等，下一轮再试）；运行中按时间续租（TTL/3），并在<b>每次复制前、每次 save 前</b>检查/续租。
 * 锁失效立即中止；慢复制结束后才发现丢锁时，删除本次未引用的复制件、保留源件与数据库行，绝不记 migrated。
 *
 * <h2>口径</h2>
 * <ul>
 * <li><b>复制不移动</b>：源文件保持原样（回滚/复核仍可对照旧件），迁移失败不会丢原始数据；</li>
 * <li><b>不覆盖</b>：目标键是新 UUID，若该路径已存在则 {@link FileAlreadyExistsException} 跳过该行，
 * 不写任何内容（也绝不删已存在的目标）；</li>
 * <li><b>缺失不改行</b>：源文件不存在（已被清理/换机）时只记 WARN，数据库行原样保留 ——
 * 不能把"文件不在"变成"引用被清空"；</li>
 * <li><b>幂等</b>：已是相对键（且能通过 {@link FileStorageService#resolve} 校验）的行直接跳过，
 * 重复执行不会产生第二份副本；</li>
 * <li><b>逐行落库</b>：不把整张表放进一个长事务；单行落库失败只回滚该行的复制件，继续处理其余行。</li>
 * </ul>
 *
 * <p>不处理 Redis 里的表单附件登记：附件是一次性件，登记簿本身带 TTL，且登记项在消费时即删除；
 * 存量绝对路径仍可由 {@link FileStorageService#resolve} 读取（历史绝对路径兼容口）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.file.key-migration", name = "enabled", havingValue = "true")
public class FileKeyMigrationRunner implements ApplicationRunner {

    /** 锁租期：覆盖一轮常规迁移；按时间（TTL/3）续期，见 {@link #RENEW_INTERVAL_NANOS}。 */
    static final Duration LOCK_TTL = Duration.ofMinutes(10);

    /** 续期间隔 = TTL/3（时间驱动；不再按扫描行数）。 */
    static final long RENEW_INTERVAL_NANOS = LOCK_TTL.dividedBy(3).toNanos();

    private final TaskFileRepository taskFileRepository;

    private final FileStorageService fileStorage;

    private final FileKeyMigrationLock lock;

    /** 单调纳秒时钟（生产 System.nanoTime；测试注入可控时钟，不必真等 TTL）。 */
    private final LongSupplier nanoClock;

    @Autowired
    public FileKeyMigrationRunner(TaskFileRepository taskFileRepository, FileStorageService fileStorage,
            FileKeyMigrationLock lock) {
        this(taskFileRepository, fileStorage, lock, System::nanoTime);
    }

    FileKeyMigrationRunner(TaskFileRepository taskFileRepository, FileStorageService fileStorage,
            FileKeyMigrationLock lock, LongSupplier nanoClock) {
        this.taskFileRepository = taskFileRepository;
        this.fileStorage = fileStorage;
        this.lock = lock;
        this.nanoClock = nanoClock;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationReport report = migrate();
        if (report.lockSkipped()) {
            log.warn("文件存储键迁移本轮未执行（未抢到单飞锁 / Redis 不可用）：{}", report);
        }
        else if (report.lockLost()) {
            log.warn("文件存储键迁移已中止（锁失效，避免与另一实例并写）：{}", report);
        }
        else if (report.failed() > 0) {
            log.warn("文件存储键迁移完成（含失败）：{}", report);
        }
        else {
            log.info("文件存储键迁移完成：{}", report);
        }
    }

    /**
     * 执行一次迁移（可重复调用）。
     *
     * <p>先抢跨实例单飞锁；抢不到返回 {@link MigrationReport#lockSkipped()}，不读表、不复制、不改行。
     *
     * @return 本次扫描/迁移计数
     */
    public MigrationReport migrate() {
        String token = "file-key-migration-" + UUID.randomUUID();
        if (!this.lock.tryAcquire(token, LOCK_TTL)) {
            return MigrationReport.skipped();
        }
        try {
            return migrateLocked(token, new AtomicLong(this.nanoClock.getAsLong()));
        }
        finally {
            this.lock.release(token);
        }
    }

    private MigrationReport migrateLocked(String token, AtomicLong lastRenewNanos) {
        int scanned = 0;
        int migrated = 0;
        int alreadyKey = 0;
        int missingSource = 0;
        int targetExists = 0;
        int invalid = 0;
        int failed = 0;
        boolean lockLost = false;

        for (TaskFile file : this.taskFileRepository.findAll()) {
            scanned++;
            String value = file.getStoragePath();
            if (value == null || value.isBlank()) {
                invalid++;
                log.warn("迁移跳过：storage_path 为空，数据库行保持不变 id={}", file.getId());
                continue;
            }

            Path raw;
            try {
                raw = Path.of(value);
            }
            catch (InvalidPathException e) {
                invalid++;
                log.warn("迁移跳过：storage_path 不是合法路径，数据库行保持不变 id={} path={}", file.getId(), value);
                continue;
            }

            if (!raw.isAbsolute()) {
                try {
                    this.fileStorage.resolve(value);
                    alreadyKey++;
                }
                catch (IllegalArgumentException e) {
                    invalid++;
                    log.warn("迁移跳过：相对键非法，数据库行保持不变 id={} key={}：{}", file.getId(), value,
                            e.getMessage());
                }
                continue;
            }

            Path source = raw.normalize();
            if (!Files.isRegularFile(source)) {
                // 缺失不改行：旧引用仍留在库里，等人工确认；不能因"文件暂时不在"清空引用。
                missingSource++;
                log.warn("迁移跳过：源文件不存在，数据库行保持不变 id={} source={}", file.getId(), source);
                continue;
            }

            StoredFile target;
            try {
                String sourceName = source.getFileName() == null ? "" : source.getFileName().toString();
                target = this.fileStorage.allocate(extensionOf(sourceName));
            }
            catch (RuntimeException e) {
                failed++;
                log.warn("迁移跳过：无法为该文件分配存储键 id={} source={}：{}", file.getId(), source,
                        e.toString());
                continue;
            }

            try {
                if (!ensureLock(token, lastRenewNanos)) {
                    lockLost = true;
                    log.warn("迁移锁续期失败（复制前），中止本次迁移以避免并发写：scanned={} migrated={}", scanned,
                            migrated);
                    break;
                }
                // 不带 REPLACE_EXISTING：目标已存在即抛 FileAlreadyExistsException（不覆盖）。
                Files.copy(source, target.path());
            }
            catch (FileAlreadyExistsException e) {
                targetExists++;
                log.warn("迁移跳过：目标文件已存在，不覆盖 id={} source={} target={}", file.getId(), source,
                        target.key());
                continue;
            }
            catch (IOException e) {
                failed++;
                log.warn("迁移失败：复制文件出错 id={} source={}：{}", file.getId(), source, e.toString());
                continue;
            }

            // 慢复制可能跨过 TTL/3：save 前必须再检查/续租。丢锁时复制件尚未被任何行引用，
            // 删掉它、保留源件与数据库行原值，绝不把这一件记成 migrated。
            if (!ensureLock(token, lastRenewNanos)) {
                lockLost = true;
                deleteUnreferencedCopy(target, source, file);
                log.warn("慢复制后发现迁移锁已失效，已删除未引用复制件并保留源件/数据库行：id={} source={}",
                        file.getId(), source);
                break;
            }

            String previous = value;
            String previousOriginal = file.getOriginalPath();
            try {
                file.setStoragePath(target.key());
                // original_path 原本就跟随 storage_path 时一并改写，避免留悬空绝对路径；
                // 与 storage_path 不同的历史值属于另一件文件，不在本次迁移范围，原样保留。
                if (previous.equals(previousOriginal)) {
                    file.setOriginalPath(target.key());
                }
                this.taskFileRepository.save(file);
                migrated++;
                log.info("迁移完成：id={} {} -> {}", file.getId(), previous, target.key());
            }
            catch (RuntimeException e) {
                failed++;
                // 落库失败：行内字段回滚为原值（JPA 侧未提交，这里只是别让内存对象说假话），
                // 复制件尚未被任何行引用，删掉它；源文件仍原样保留（复制不移动）。
                file.setStoragePath(previous);
                file.setOriginalPath(previousOriginal);
                this.fileStorage.delete(target.key());
                log.warn("迁移失败：数据库行更新失败，已删除复制件 id={} source={} target={}：{}", file.getId(),
                        source, target.key(), e.toString());
            }
        }

        return new MigrationReport(scanned, migrated, alreadyKey, missingSource, targetExists, invalid, failed,
                false, lockLost);
    }

    /**
     * 时间驱动检查/续租：距上次续租达到 TTL/3 才真正调 {@code renew}（每行一次 Redis 往返没必要，
     * 但也不能按行数 —— 单次慢 {@code Files.copy} 可能跨过整个 TTL）。返回 false = 锁已失效。
     */
    private boolean ensureLock(String token, AtomicLong lastRenewNanos) {
        long now = this.nanoClock.getAsLong();
        if (now - lastRenewNanos.get() < RENEW_INTERVAL_NANOS) {
            return true;
        }
        if (!this.lock.renew(token, LOCK_TTL)) {
            return false;
        }
        lastRenewNanos.set(now);
        return true;
    }

    /** 丢锁后清理本次复制件：源件与数据库行都不动，删除失败只留 WARN（孤儿待人工清理）。 */
    private void deleteUnreferencedCopy(StoredFile target, Path source, TaskFile file) {
        try {
            this.fileStorage.delete(target.key());
        }
        catch (RuntimeException e) {
            log.warn("删除未引用复制件失败（孤儿，待人工清理） id={} source={} target={}：{}", file.getId(), source,
                    target.key(), e.toString());
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    /**
     * 迁移计数（幂等判据：第二遍 {@code migrated=0}、{@code alreadyKey=首遍全部行}）。
     *
     * <p>{@code lockSkipped=true}：本轮没抢到单飞锁（另一实例在迁移或 Redis 不可用），未读表；
     * {@code lockLost=true}：运行中锁续期失败，已提前中止；丢锁前已提交的行幂等，
     * 丢锁时正在复制的未引用件已删除（源件与数据库行保留）。
     */
    public record MigrationReport(int scanned, int migrated, int alreadyKey, int missingSource, int targetExists,
            int invalid, int failed, boolean lockSkipped, boolean lockLost) {

        static MigrationReport skipped() {
            return new MigrationReport(0, 0, 0, 0, 0, 0, 0, true, false);
        }
    }
}
