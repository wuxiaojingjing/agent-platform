package com.huawei.finance.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SlotNames;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 模型语义槽位到账户域权威事实的确定性映射。 */
class AccountReferenceResolverTest {

    private final AccountReferenceResolver resolver = new AccountReferenceResolver();

    @Test
    @DisplayName("第二张卡 + 一半 → 只解析账户与延迟求值依据")
    void resolvesOrdinalAndDeferredAmountBasisWithoutCalculatingAmount() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(SlotNames.PAYEE, "张三");
        slots.put(SlotNames.ACCOUNT_ORDINAL, 2);
        slots.put(SlotNames.AMOUNT_BASIS, AccountReferenceResolver.REQUERY_THEN_HALF);

        Map<String, Object> enriched = resolver.resolve(slots, leaseWithCards(), "用第二张卡转一半给张三");

        assertThat(enriched.get(SlotNames.FROM_ACCOUNT)).isEqualTo("尾号 3344 借记卡");
        assertThat(enriched)
                .containsEntry(SlotNames.PAYEE, "张三")
                .containsEntry(AccountReferenceResolver.ACCOUNT_ORDINAL_SLOT, 2)
                .containsEntry(AccountReferenceResolver.AMOUNT_BASIS_SLOT, "REQUERY_THEN_HALF")
                .doesNotContainKey(SlotNames.AMOUNT);
    }

    @Test
    @DisplayName("无 cards 时保留语义槽位，不强行编造")
    void keepsSemanticSlotsWhenNoCards() {
        Map<String, Object> slots = Map.of(
                SlotNames.ACCOUNT_ORDINAL, 2,
                SlotNames.AMOUNT_BASIS, AccountReferenceResolver.REQUERY_THEN_HALF);

        Map<String, Object> enriched = resolver.resolve(slots, emptyLease(), "用第二张卡转一半给张三");

        assertThat(enriched.get(SlotNames.ACCOUNT_ORDINAL)).isEqualTo(2);
        assertThat(enriched.get(SlotNames.AMOUNT_BASIS)).isEqualTo("REQUERY_THEN_HALF");
    }

    @Test
    @DisplayName("序号越界时不映射账户和金额，也不回退第一张卡")
    void outOfRangeOrdinalDoesNotResolveAnAccount() {
        Map<String, Object> slots = Map.of(
                SlotNames.ACCOUNT_ORDINAL, 4,
                SlotNames.AMOUNT_BASIS, AccountReferenceResolver.REQUERY_THEN_HALF);

        Map<String, Object> enriched = resolver.resolve(slots, leaseWithCards(), "第四张呢");

        assertThat(enriched)
                .containsEntry(SlotNames.ACCOUNT_ORDINAL, 4)
                .doesNotContainKeys(SlotNames.FROM_ACCOUNT, SlotNames.AMOUNT,
                        AccountReferenceResolver.ACCOUNT_ORDINAL_SLOT,
                        AccountReferenceResolver.AMOUNT_BASIS_SLOT);
    }

    private static ContextLease leaseWithCards() {
        List<Map<String, Object>> cards = List.of(
                Map.of("index", 1, "alias", "尾号 8821 借记卡", "availableBalance", "12,845.60"),
                Map.of("index", 2, "alias", "尾号 3344 借记卡", "availableBalance", "8,000.00"),
                Map.of("index", 3, "alias", "尾号 5566 信用卡", "availableBalance", "3,000.00"));
        ContextLease.ToolConclusion conclusion = new ContextLease.ToolConclusion(
                "cap.account.balance.query", Enums.ToolOutcome.SUCCEEDED, Enums.PendingAction.NONE,
                Map.of("cards", cards, "accountAlias", "尾号 8821 借记卡", "availableBalance", "12,845.60"));
        return new ContextLease(UUID.randomUUID().toString(), "s-1", "转账", Map.of(), List.of(),
                List.of(conclusion), 4000, 100, List.of(), true, 1L, Instant.now().plusSeconds(60));
    }

    private static ContextLease emptyLease() {
        return new ContextLease(UUID.randomUUID().toString(), "s-1", "转账", Map.of(), List.of(),
                List.of(), 4000, 10, List.of(), true, 0L, Instant.now().plusSeconds(60));
    }
}
