package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link FileStorageService} 两个事务回调的异常边界（I1）。
 *
 * <p>回调跑在事务完成链上，与业务无关但<b>同一线程同一批</b>：任一回调抛出的运行时异常会中断
 * Spring 的同步器遍历，排在后面的同步器（典型：{@code JobTerminalWriter} 的终态落写、
 * 回滚收敛）就永远不执行。故回调内部必须自兜底：只 WARN，不向外抛。
 *
 * <p>用例直接驱动 Spring 的同步器回调工具（{@code TransactionSynchronizationUtils}），
 * 注册顺序 = 真实提交/回滚时的调用顺序。
 */
class FileStorageServiceTransactionCallbackTest {

    /** 非法路径：{@code Path.of} 抛 {@code InvalidPathException}（RuntimeException，非 IOException）。 */
    private static final String UNPARSEABLE_PATH = "bad\u0000path.xlsx";

    private final FileStorageService storage = new FileStorageService(new AppProperties());

    @Test
    void deleteAfterCommitSwallowsRuntimeExceptionAndKeepsLaterSynchronizationsAlive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            this.storage.deleteAfterCommit(UNPARSEABLE_PATH);
            AtomicBoolean laterRan = new AtomicBoolean(false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    laterRan.set(true);
                }
            });

            assertThatCode(() -> TransactionSynchronizationUtils
                    .invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations()))
                .as("非法路径不得把 afterCommit 链打断")
                .doesNotThrowAnyException();
            assertThat(laterRan).as("排在后面的同步器必须照常执行").isTrue();
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteAfterRollbackSwallowsRuntimeExceptionAndKeepsLaterSynchronizationsAlive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            this.storage.deleteAfterRollback(UNPARSEABLE_PATH);
            AtomicBoolean laterRan = new AtomicBoolean(false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    laterRan.set(true);
                }
            });
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();

            // invokeAfterCompletion 会吞掉每个同步器的异常（只记 ERROR 日志），所以这里直接调用
            // 本次登记的那一个：I1 要求异常在回调内部就兜住并以 WARN 留痕。
            assertThatCode(() -> synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                .as("非法路径不得从本同步器抛出")
                .doesNotThrowAnyException();
            assertThatCode(() -> synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED))
                .as("提交态同理（本同步器不该在这里抛）")
                .doesNotThrowAnyException();

            assertThatCode(() -> TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                    TransactionSynchronization.STATUS_ROLLED_BACK))
                .doesNotThrowAnyException();
            assertThat(laterRan).as("排在后面的同步器必须照常执行").isTrue();
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void wellFormedKeysAreStillDeletedOnCommitAndRollback() throws Exception {
        Path root = Files.createTempDirectory("flc-tx-");
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(root.toString());
        FileStorageService keyed = new FileStorageService(properties);
        StoredFile commitFile = keyed.allocate(".xlsx");
        StoredFile rollbackFile = keyed.allocate(".xlsx");
        keyed.write(commitFile.path(), new byte[] { 1 });
        keyed.write(rollbackFile.path(), new byte[] { 2 });
        try {
            TransactionSynchronizationManager.initSynchronization();
            keyed.deleteAfterCommit(commitFile.key());
            keyed.deleteAfterRollback(rollbackFile.key());
            TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
            TransactionSynchronizationUtils.invokeAfterCompletion(
                    TransactionSynchronizationManager.getSynchronizations(),
                    TransactionSynchronization.STATUS_ROLLED_BACK);

            assertThat(commitFile.path()).doesNotExist();
            assertThat(rollbackFile.path()).doesNotExist();
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
            Files.deleteIfExists(commitFile.path());
            Files.deleteIfExists(rollbackFile.path());
            Files.deleteIfExists(root);
        }
    }

    @Test
    void keyBasedRollbackRegistrationDeletesOnlyWhenTheTransactionRollsBack() throws Exception {
        Path root = Files.createTempDirectory("flc-key-tx-");
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(root.toString());
        FileStorageService keyed = new FileStorageService(properties);
        StoredFile kept = keyed.allocate(".xlsx");
        StoredFile dropped = keyed.allocate(".xlsx");
        keyed.write(kept.path(), new byte[] { 1 });
        keyed.write(dropped.path(), new byte[] { 2 });

        try {
            TransactionSynchronizationManager.initSynchronization();
            keyed.deleteAfterRollback(kept.key());
            keyed.deleteAfterRollback(dropped.key());
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();

            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(kept.path()).as("提交成功：本次新件保留").exists();

            synchronizations.get(1).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(dropped.path()).as("回滚：按存储键解析出的新件被回收").doesNotExist();
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
            Files.deleteIfExists(kept.path());
            Files.deleteIfExists(dropped.path());
            Files.deleteIfExists(root);
        }
    }

}
