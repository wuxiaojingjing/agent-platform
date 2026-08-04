package com.huawei.finance.registry.index;

import com.huawei.finance.registry.asset.AssetBundle;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 索引重建管道：资产发布后自动把 OpenSearch 别名切到与当前资产版本一致的索引。
 *
 * <p>FP-46。原先只在启动期建一次索引，控制台改能力卡后要人点「重建索引」或重启——
 * 漏了就出现「卡已发布、问法召不回来、也不报错」。管道挂在 {@code AssetStore.onReload}
 * 上，版本一变就同步重建；失败不切半成品别名，并恢复旧就绪态。
 *
 * <p>同版本且索引已就绪时跳过，避免回滚或无实质改动的重载白跑一遍 embedding。
 */
public class IndexRebuildPipeline {

    private static final Logger log = LoggerFactory.getLogger(IndexRebuildPipeline.class);

    @FunctionalInterface
    interface RebuildAction {
        boolean rebuild(AssetBundle bundle) throws IOException;
    }

    private final RebuildAction rebuildAction;
    private final IndexReadiness readiness;
    private final RegistryProperties props;
    private final Object lock = new Object();

    public IndexRebuildPipeline(CapabilityIndexer indexer, IndexReadiness readiness,
                                RegistryProperties props) {
        this(indexer::rebuild, readiness, props);
    }

    /** 测试用：注入替身，不接真实 OpenSearch。 */
    IndexRebuildPipeline(RebuildAction rebuildAction, IndexReadiness readiness,
                         RegistryProperties props) {
        this.rebuildAction = rebuildAction;
        this.readiness = readiness;
        this.props = props;
    }

    /**
     * 资产重载回调。关闭自动重建时（外部流水线接管）直接跳过。
     */
    public Result onAssetReload(AssetBundle bundle) {
        if (!props.isRebuildOnAssetChange()) {
            log.info("已关闭资产变更自动重建，跳过 version={}", bundle.assetVersion());
            return Result.skipped(bundle.assetVersion(), "rebuildOnAssetChange=false");
        }
        return rebuildIfNeeded(bundle, false);
    }

    /** 启动或控制台强制重建，不受「已与资产同版本」短路。 */
    public Result forceRebuild(AssetBundle bundle) {
        return rebuildIfNeeded(bundle, true);
    }

    /**
     * 启动期强制重建并对短暂的依赖不可用做有限重试。
     *
     * <p>容器健康检查只能降低启动竞争概率，不能保证一次连接必然成功。重试耗尽后仍返回
     * FAILED，让调用方按原有规则摘除检索通道，不阻止应用提供规则能力。
     */
    public Result forceRebuildWithRetry(AssetBundle bundle, int maxAttempts, long retryDelayMs) {
        int attempts = Math.max(1, maxAttempts);
        long delayMs = Math.max(0L, retryDelayMs);
        Result result = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            result = forceRebuild(bundle);
            if (result.outcome() != Outcome.FAILED || attempt == attempts) {
                return result;
            }
            log.warn("启动索引构建失败，将重试 attempt={}/{} delayMs={} detail={}",
                    attempt, attempts, delayMs, result.detail());
            if (delayMs > 0 && !awaitRetry(delayMs)) {
                return Result.failed(bundle.assetVersion(), "interrupted-before-retry");
            }
        }
        return result;
    }

    private boolean awaitRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("启动索引构建重试被中断");
            return false;
        }
    }

    private Result rebuildIfNeeded(AssetBundle bundle, boolean force) {
        synchronized (lock) {
            IndexReadiness.Snapshot snap = readiness.get();
            if (!force
                    && snap.searchable()
                    && bundle.assetVersion().equals(snap.assetVersion())) {
                log.info("索引已与资产版本一致，跳过重建 version={}", bundle.assetVersion());
                return Result.skipped(bundle.assetVersion(), "already-in-sync");
            }

            try {
                boolean vectors = rebuildAction.rebuild(bundle);
                IndexReadiness.Snapshot after = readiness.get();
                log.info("索引重建完成 version={} 文档数={} 向量={}",
                        bundle.assetVersion(), after.documentCount(), vectors);
                return Result.rebuilt(bundle.assetVersion(), vectors, after.documentCount(),
                        after.indexName());
            } catch (IOException | RuntimeException e) {
                readiness.restoreAfterFailedBuild();
                log.warn("索引重建失败，检索继续使用旧索引 version={} cause={}",
                        bundle.assetVersion(), e.toString());
                return Result.failed(bundle.assetVersion(), e.toString());
            }
        }
    }

    /**
     * @param outcome        结果种类
     * @param assetVersion   目标资产版本
     * @param vectorsIndexed 是否写入向量；跳过/失败时为 false
     * @param documentCount  就绪后的文档数
     * @param indexName      就绪后的物理索引名
     * @param detail         跳过原因或失败信息
     */
    public record Result(Outcome outcome, String assetVersion, boolean vectorsIndexed,
                         int documentCount, String indexName, String detail) {

        public boolean effective() {
            return outcome == Outcome.REBUILT || outcome == Outcome.SKIPPED;
        }

        static Result skipped(String assetVersion, String reason) {
            return new Result(Outcome.SKIPPED, assetVersion, false, 0, null, reason);
        }

        static Result rebuilt(String assetVersion, boolean vectors, int docs, String indexName) {
            return new Result(Outcome.REBUILT, assetVersion, vectors, docs, indexName, null);
        }

        static Result failed(String assetVersion, String detail) {
            return new Result(Outcome.FAILED, assetVersion, false, 0, null, detail);
        }
    }

    public enum Outcome {
        REBUILT,
        SKIPPED,
        FAILED
    }
}
