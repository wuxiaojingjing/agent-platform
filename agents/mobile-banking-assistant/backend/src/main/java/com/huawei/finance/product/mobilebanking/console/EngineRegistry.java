package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.intent.IntentEngineFactory;
import com.huawei.finance.intent.bootstrap.IntentEngines;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetStore;
import com.huawei.finance.response.AnswerAudit;
import com.huawei.finance.response.ResponsePlanner;
import com.huawei.finance.response.ResponseProperties;
import com.huawei.finance.response.ResponseRealizer;
import com.huawei.finance.response.ResponseTextModel;
import com.huawei.finance.response.TemplateVariableValidator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 当前生效的那套引擎，以及资产换掉时重建它的那段逻辑。
 *
 * <p>「换引用而不是改内容」是这里唯一的设计：{@link AssetBundle} 与由它装出来的组件全部
 * 保持不可变，重载时整套重建、一次性换掉 {@code volatile} 引用。在途请求在开头已经取过
 * 快照，会用完那一套旧的跑到底——这比让它中途换阈值安全得多。
 *
 * <p>重建**不重建** Redis 连接、HanLP 词典这些跨版本共享物，它们由
 * {@link IntentEngines.Infrastructure} 带着复用。每次重载都重新加载一遍词典，
 * 会让一次配置改动变成几秒钟的停顿。
 */
@Component
public class EngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    private final AssetStore store;
    private final IntentEngines.Infrastructure infra;
    private final IntentEngineFactory intentEngineFactory;
    private final TemplateVariableValidator variableValidator;
    private final ContractValidator contractValidator;
    private final ResponseProperties responseProps;
    private final MeterRegistry meterRegistry;
    private final AnswerAudit answerAudit;
    private final ResponseTextModel responseTextModel;

    private volatile EngineSnapshot snapshot;

    public EngineRegistry(AssetStore store, IntentEngines.Infrastructure infra,
                          IntentEngineFactory intentEngineFactory,
                          TemplateVariableValidator variableValidator,
                          ContractValidator contractValidator, ResponseProperties responseProps,
                          MeterRegistry meterRegistry, AnswerAudit answerAudit,
                          ResponseTextModel responseTextModel) {
        this.store = store;
        this.infra = infra;
        this.intentEngineFactory = intentEngineFactory;
        this.variableValidator = variableValidator;
        this.contractValidator = contractValidator;
        this.responseProps = responseProps;
        this.meterRegistry = meterRegistry;
        this.answerAudit = answerAudit;
        this.responseTextModel = responseTextModel;
        this.snapshot = build(store.current());
    }

    /**
     * 挂上重载回调。
     *
     * <p>放在 {@code @PostConstruct} 而不是构造函数里：构造期这个对象还没完全建好，
     * 而回调一旦注册就可能被别的线程触发，那时它拿到的是一个半成品。
     */
    @PostConstruct
    void subscribe() {
        store.onReload(bundle -> {
            snapshot = build(bundle);
            log.info("引擎已按新资产重建 version={}", bundle.assetVersion());
        });
    }

    /**
     * 取本次请求要用的那一套。
     *
     * <p>**一次请求只调一次。**调两次就可能横跨两个资产版本，那正是这个类要防的事。
     */
    public EngineSnapshot current() {
        return snapshot;
    }

    private EngineSnapshot build(AssetBundle bundle) {
        return new EngineSnapshot(
                bundle,
                IntentEngines.fastPath(bundle, infra, intentEngineFactory),
                new ResponsePlanner(bundle, contractValidator, responseProps, meterRegistry),
                new ResponseRealizer(bundle, variableValidator, meterRegistry, answerAudit, responseTextModel));
    }
}
