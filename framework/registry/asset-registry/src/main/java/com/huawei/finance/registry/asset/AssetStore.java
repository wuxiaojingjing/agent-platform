package com.huawei.finance.registry.asset;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 当前生效的资产，以及换掉它的唯一入口。
 *
 * <p>原先的设计是「启动期一次性加载，运行中不可换」，理由有两条：资产版本参与出口缓存键，
 * 也参与索引名。现在只剩后一条成立——
 *
 * <ul>
 *   <li><b>缓存那条不成立</b>：{@code DecisionCacheKey} 里带的是含内容摘要的
 *       {@code assetVersion}。资产一变摘要就变，旧结论对应的键再也不会被查到，
 *       新旧结论在 Redis 里共存但不可能混用。这正是当初把摘要放进版本号的目的。</li>
 *   <li><b>索引那条成立</b>：检索走的是别名，换资产不会把检索打瞎，但 BM25 与语义通道
 *       会继续服务上一次建好的索引。所以改能力卡文本必须连带重建索引，
 *       否则新写的问法召不回来——而且完全没有报错。{@code IndexRebuildPipeline}
 *       挂在 {@link #onReload} 上自动重建；{@code IndexReadiness.assetVersion}
 *       与当前版本不一致时仍作 stale 告警（重建失败或外部接管时）。</li>
 * </ul>
 *
 * <p><b>{@link AssetBundle} 仍然是不可变的。</b>换的是这里的引用，不是它的内容。
 * 使用方必须在一次请求的开头取一次快照并全程用它——中途各取各的，会出现同一个请求里
 * 一半组件按新阈值判、另一半按旧模板渲染，而这种不一致既不报错也无法复现。
 */
public class AssetStore {

    private static final Logger log = LoggerFactory.getLogger(AssetStore.class);

    private final AssetLoader loader;
    private final Path root;
    private final List<Consumer<AssetBundle>> listeners = new CopyOnWriteArrayList<>();

    private volatile AssetBundle current;

    public AssetStore(AssetLoader loader, Path root) {
        this.loader = loader;
        this.root = root;
        this.current = loader.load(root);
    }

    public AssetBundle current() {
        return current;
    }

    public Path root() {
        return root;
    }

    /**
     * 重新从磁盘加载并切换。
     *
     * <p>加载失败就地抛出且**不换**当前资产：一份读不出来的资产换上去等于让系统失去判断
     * 依据，而继续用旧的至少还能正常服务。调用方据此把失败报给操作者。
     *
     * @return 切换后的资产
     */
    public AssetBundle reload() {
        AssetBundle loaded = loader.load(root);
        AssetBundle previous = current;
        current = loaded;

        if (previous.assetVersion().equals(loaded.assetVersion())) {
            log.info("资产重载后版本未变 version={}，内容摘要一致说明这次改动没有实质变化",
                    loaded.assetVersion());
        } else {
            log.info("资产已切换 {} → {}", previous.assetVersion(), loaded.assetVersion());
        }

        for (Consumer<AssetBundle> listener : listeners) {
            try {
                listener.accept(loaded);
            } catch (RuntimeException e) {
                // 一个回调失败不能拦住引擎重建或索引管道：资产引用已经换了，
                // 半套监听成功、半套没跑会造成更难诊断的漂移。
                log.error("资产重载回调失败 version={} cause={}",
                        loaded.assetVersion(), e.toString());
            }
        }
        return loaded;
    }

    /**
     * 注册重载回调，用于重建那些持有资产的对象图。
     *
     * <p>回调里应当**整体重建**再换引用，而不是逐个字段改。快路径那一串组件全是构造期就把
     * 资产拆进 final 字段的，逐个改既做不到也不该做。
     */
    public void onReload(Consumer<AssetBundle> listener) {
        listeners.add(listener);
    }
}
