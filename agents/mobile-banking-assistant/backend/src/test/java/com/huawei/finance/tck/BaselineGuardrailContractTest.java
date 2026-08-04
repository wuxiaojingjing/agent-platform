package com.huawei.finance.tck;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.orchestrator.guardrail.GuardrailProperties;
import com.huawei.finance.orchestrator.guardrail.PolicyGuardrail;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;

/**
 * 基线自己的 {@link PolicyGuardrail} 跑一遍 TCK。
 *
 * <p>TCK 也是代码，也会写错，而它写错的方式很隐蔽：一套永远绿的契约用例，
 * 交出去之后行内跑通了、以为合规了，实际什么都没验。所以基线实现必须先跑一遍——
 * 这至少证明这些用例是可满足的，不是自相矛盾或者断言反了。
 *
 * <p>「能抓住违规实现」由 {@code TckDetectsViolationsTest} 从反面证明。两者缺一：
 * 只有这边说明用例不苛刻，只有那边说明用例不宽松。
 */
@DisplayName("基线护栏通过 TCK")
class BaselineGuardrailContractTest extends GuardrailHookContract {

    private static final AssetBundle BUNDLE =
            new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());

    @Override
    protected GuardrailHook guardrail() {
        return new PolicyGuardrail(new GuardrailProperties());
    }

    @Override
    protected CapabilityCard r2Card() {
        return BUNDLE.capability("cap.transfer");
    }

    @Override
    protected Map<String, Object> completeParameters() {
        return Map.of("payee", "张三", "amount", "100");
    }
}
