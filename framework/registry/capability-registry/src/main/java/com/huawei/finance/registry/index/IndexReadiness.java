package com.huawei.finance.registry.index;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引就绪状态。
 *
 * <p>v0.7 §5.1 与本工程约束 4：能力卡发布后索引未就绪则**不参与召回**，
 * 禁止「卡已发布、索引仍旧」的中间态。没有这个状态位，发布后的短暂窗口里
 * 用户会被按旧索引路由，且没有任何迹象表明发生过什么。
 *
 * <p>{@code vectorsIndexed} 单独一位：模型网关在建索引时不可用，BM25 字段照样能写，
 * 只是向量缺失。这时索引是「就绪但无语义通道」，与「完全不可用」是两种状态，
 * 混为一谈会导致断网时整个召回停摆。
 */
public class IndexReadiness {

    /** 索引状态。 */
    public enum State {
        /** 尚未建立可用索引，召回的检索通道必须整体停用。 */
        NOT_READY,
        /** 正在重建，此时旧索引仍通过别名提供服务。 */
        BUILDING,
        /** 别名已指向新索引，可参与召回。 */
        READY
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.NOT_READY, null, null, false, 0));

    /**
     * @param state          当前状态
     * @param indexName      别名当前指向的物理索引
     * @param assetVersion   索引对应的资产版本
     * @param vectorsIndexed 是否成功写入向量；为 false 时语义通道不可用
     * @param documentCount  索引内的能力卡数量
     */
    public record Snapshot(State state, String indexName, String assetVersion,
                           boolean vectorsIndexed, int documentCount) {

        public boolean searchable() {
            return state == State.READY;
        }

        public boolean semanticAvailable() {
            return state == State.READY && vectorsIndexed;
        }
    }

    public Snapshot get() {
        return current.get();
    }

    public void markBuilding() {
        Snapshot prev = current.get();
        current.set(new Snapshot(State.BUILDING, prev.indexName(), prev.assetVersion(),
                prev.vectorsIndexed(), prev.documentCount()));
    }

    public void markReady(String indexName, String assetVersion, boolean vectorsIndexed, int documentCount) {
        current.set(new Snapshot(State.READY, indexName, assetVersion, vectorsIndexed, documentCount));
    }

    public void markNotReady() {
        current.set(new Snapshot(State.NOT_READY, null, null, false, 0));
    }

    /**
     * 重建失败时回到可服务状态。
     *
     * <p>{@link #markBuilding()} 把状态改成 BUILDING，但旧别名的元数据还留在快照里。
     * 重建抛错时别名并未切换（切别名在灌数之后），所以把状态翻回 READY 就能继续用旧索引；
     * 若从来没有过可用索引，则回到 {@link State#NOT_READY}。
     *
     * <p>若不做这一步，失败后会卡在 BUILDING：{@code searchable()} 为假，检索通道整片摘除，
     * 比「资产新、索引旧」更糟。
     */
    public void restoreAfterFailedBuild() {
        Snapshot prev = current.get();
        if (prev.indexName() != null && prev.assetVersion() != null) {
            current.set(new Snapshot(State.READY, prev.indexName(), prev.assetVersion(),
                    prev.vectorsIndexed(), prev.documentCount()));
        } else {
            markNotReady();
        }
    }
}
