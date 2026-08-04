package com.huawei.finance.registry.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-46：资产变更触发重建；同版本跳过；失败恢复旧就绪态且不把别名留在半成品。
 *
 * <p>不接 OpenSearch——替身只记录调用次数与成败，管道的分支逻辑才是本类要验的。
 */
class IndexRebuildPipelineTest {

    private AssetBundle bundle;
    private IndexReadiness readiness;
    private RegistryProperties props;

    @BeforeEach
    void setUp() {
        bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        readiness = new IndexReadiness();
        props = new RegistryProperties();
        props.setRebuildOnAssetChange(true);
    }

    @Test
    @DisplayName("资产版本变了 → 自动重建并回执 REBUILT")
    void rebuildsWhenAssetVersionDiffers() {
        readiness.markReady("old-index", "old-version", true, 3);
        AtomicInteger calls = new AtomicInteger();

        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            readiness.markReady("new-index", b.assetVersion(), true, 10);
            return true;
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.onAssetReload(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.REBUILT);
        assertThat(result.effective()).isTrue();
        assertThat(result.assetVersion()).isEqualTo(bundle.assetVersion());
        assertThat(result.vectorsIndexed()).isTrue();
        assertThat(result.documentCount()).isEqualTo(10);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(readiness.get().assetVersion()).isEqualTo(bundle.assetVersion());
    }

    @Test
    @DisplayName("索引已与资产同版本且就绪 → 跳过，不二次灌数")
    void skipsWhenAlreadyInSync() {
        readiness.markReady("idx", bundle.assetVersion(), true, 5);
        AtomicInteger calls = new AtomicInteger();

        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            return true;
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.onAssetReload(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.SKIPPED);
        assertThat(result.detail()).isEqualTo("already-in-sync");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("forceRebuild 无视同版本短路")
    void forceRebuildIgnoresSkip() {
        readiness.markReady("idx", bundle.assetVersion(), true, 5);
        AtomicInteger calls = new AtomicInteger();

        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            readiness.markReady("idx-2", b.assetVersion(), false, 5);
            return false;
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.forceRebuild(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.REBUILT);
        assertThat(result.vectorsIndexed()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("启动重建短暂失败 → 在上限内重试并恢复")
    void startupRebuildRetriesTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            if (calls.incrementAndGet() < 3) {
                throw new java.net.ConnectException("OpenSearch 尚未就绪");
            }
            readiness.markReady("idx", b.assetVersion(), true, 10);
            return true;
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.forceRebuildWithRetry(bundle, 3, 0);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.REBUILT);
        assertThat(result.vectorsIndexed()).isTrue();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(readiness.get().semanticAvailable()).isTrue();
    }

    @Test
    @DisplayName("启动重建持续失败 → 达到上限后保持受控降级")
    void startupRebuildStopsAtAttemptLimit() {
        AtomicInteger calls = new AtomicInteger();
        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            throw new java.net.ConnectException("OpenSearch 不可达");
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.forceRebuildWithRetry(bundle, 3, 0);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.FAILED);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(readiness.get().state()).isEqualTo(IndexReadiness.State.NOT_READY);
    }

    @Test
    @DisplayName("重建失败 → FAILED，就绪态回到旧索引，searchable 仍为真")
    void failureRestoresPreviousReadyState() {
        readiness.markReady("old-index", "old-version", true, 7);
        AtomicInteger calls = new AtomicInteger();

        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            readiness.markBuilding();
            throw new IllegalStateException("bulk 灌数失败");
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.onAssetReload(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.FAILED);
        assertThat(result.effective()).isFalse();
        assertThat(calls.get()).isEqualTo(1);

        IndexReadiness.Snapshot snap = readiness.get();
        assertThat(snap.state()).isEqualTo(IndexReadiness.State.READY);
        assertThat(snap.indexName()).isEqualTo("old-index");
        assertThat(snap.assetVersion()).isEqualTo("old-version");
        assertThat(snap.searchable()).isTrue();
        assertThat(snap.documentCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("从未就绪时重建失败 → 回到 NOT_READY，而不是卡在 BUILDING")
    void failureFromScratchLeavesNotReady() {
        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            readiness.markBuilding();
            throw new java.io.IOException("OpenSearch 不可达");
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.forceRebuild(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.FAILED);
        assertThat(readiness.get().state()).isEqualTo(IndexReadiness.State.NOT_READY);
        assertThat(readiness.get().searchable()).isFalse();
    }

    @Test
    @DisplayName("关闭 rebuildOnAssetChange 时重载不建索引")
    void respectsRebuildOnAssetChangeFlag() {
        props.setRebuildOnAssetChange(false);
        AtomicInteger calls = new AtomicInteger();
        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(b -> {
            calls.incrementAndGet();
            return true;
        }, readiness, props);

        IndexRebuildPipeline.Result result = pipeline.onAssetReload(bundle);

        assertThat(result.outcome()).isEqualTo(IndexRebuildPipeline.Outcome.SKIPPED);
        assertThat(result.detail()).contains("rebuildOnAssetChange");
        assertThat(calls.get()).isZero();
    }
}
