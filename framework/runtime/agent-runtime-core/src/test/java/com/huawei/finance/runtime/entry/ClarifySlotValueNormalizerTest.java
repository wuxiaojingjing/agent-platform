package com.huawei.finance.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.registry.asset.ClarifyConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClarifySlotValueNormalizerTest {

    private final ClarifySlotValueNormalizer normalizer =
            new ClarifySlotValueNormalizer(ClarifySlotValueNormalizerTest::clarify);

    @Test
    void keepsDisplayValueAndMapsAliasOrCanonicalValueToThePrimaryOption() {
        List<String> allowed = List.of("信用卡", "借记卡");

        assertThat(normalizer.normalize("cardType", "信用卡", allowed)).isEqualTo("信用卡");
        assertThat(normalizer.normalize("cardType", "贷记卡", allowed)).isEqualTo("信用卡");
        assertThat(normalizer.normalize("cardType", "CREDIT", allowed)).isEqualTo("信用卡");
        assertThat(normalizer.normalize("cardType", "储蓄卡", allowed)).isEqualTo("借记卡");
        assertThat(normalizer.normalize("cardType", "工资卡", allowed)).isEqualTo("借记卡");
        assertThat(normalizer.normalize("cardType", "DEBIT", allowed)).isEqualTo("借记卡");
    }

    @Test
    void leavesUnknownCollectionMemberForPolicyGateToReject() {
        assertThat(normalizer.normalize("cardType", "公交卡", List.of("信用卡", "借记卡")))
                .isEqualTo("公交卡");
    }

    private static ClarifyConfig clarify() {
        ClarifyConfig.SlotClarify cardType = new ClarifyConfig.SlotClarify();
        cardType.setOptions(List.of("信用卡", "借记卡"));
        cardType.setValueMapping(Map.of(
                "信用卡", "CREDIT", "贷记卡", "CREDIT",
                "借记卡", "DEBIT", "储蓄卡", "DEBIT", "工资卡", "DEBIT"));
        ClarifyConfig config = new ClarifyConfig();
        config.setSlots(Map.of("cardType", cardType));
        return config;
    }
}
