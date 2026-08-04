package com.huawei.finance.runtime.loop;

import static com.huawei.finance.orchestrator.loop.LoopContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.registry.asset.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopReadOnlyActionExecutorTest {
    private final LoopActionExecutorRouter router = new LoopActionExecutorRouter(
            request -> { throw new AssertionError("只读资产动作不得进入 TaskOrchestrator"); });

    @Test
    void knowledgeAndMenuActionsUseVersionedAssets() {
        AssetBundle assets = assets();
        Run run = run();
        Observation knowledge = router.execute(context(), run,
                new Action(ActionType.SEARCH_KNOWLEDGE, null, Map.of(), Map.of(), "LOOKUP", "fp-k"),
                null, lease(), false, assets);
        assertThat(knowledge.status()).isEqualTo(ObservationStatus.SUCCESS);
        assertThat(knowledge.sourceType()).isEqualTo("KNOWLEDGE");
        assertThat(knowledge.displayHints()).containsEntry("answer", "工资通常在发薪日当日到账");

        CapabilityCard navigation = assets.capability("cap.nav.account_查询账户余额");
        Observation menu = router.execute(context(), run,
                new Action(ActionType.RESOLVE_MENU, navigation.capabilityId(), Map.of(), Map.of(), "NAV", "fp-m"),
                navigation, lease(), false, assets);
        assertThat(menu.status()).isEqualTo(ObservationStatus.SUCCESS);
        assertThat(menu.facts()).containsEntry("menuId", "menu.account.查询账户余额");
        assertThat(menu.displayHints()).containsEntry("action", "OPEN_MENU");
    }

    @Test
    void validatorRejectsTargetsOutsideCandidateSnapshot() {
        LoopActionValidator validator = new LoopActionValidator();
        Action outside = new Action(ActionType.RESOLVE_MENU, "cap.nav.outside", Map.of(), Map.of(), "NAV",
                LoopActionFingerprint.of(ActionType.RESOLVE_MENU, "cap.nav.outside", Map.of()));
        Run run = run();
        LoopContext loopContext = new LoopContext(run, Map.of(), null, List.of(), 3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(outside,
                List.of(assets().capability("cap.nav.account_查询账户余额")), loopContext))
                .hasMessage("LOOP_TARGET_OUTSIDE_CANDIDATES");
    }

    @Test
    void a2aSelfCycleAndDepthAreRejectedBeforeDelegation() {
        LoopActionExecutorRouter a2a = new LoopActionExecutorRouter(
                request -> { throw new AssertionError("A2A 不得进入 TaskOrchestrator"); },
                (context, run, action, card) -> { throw new AssertionError("非法委托不得发送"); }, 2);
        Action self = new Action(ActionType.DELEGATE_GOAL, "agent.test", Map.of(), Map.of(), "GOAL", "self");
        Action cycle = new Action(ActionType.DELEGATE_GOAL, "agent.previous", Map.of(), Map.of(), "GOAL", "cycle");
        Action depth = new Action(ActionType.DELEGATE_GOAL, "agent.next", Map.of(), Map.of(), "GOAL", "depth");

        assertThat(a2a.execute(context(), run(), self, null, lease(), false, assets()).reasonCode())
                .isEqualTo("A2A_SELF_DELEGATION");
        assertThat(a2a.execute(contextWithPath(List.of("agent.root", "agent.previous")), run(), cycle,
                null, lease(), false, assets()).reasonCode()).isEqualTo("A2A_CYCLE_DETECTED");
        assertThat(a2a.execute(contextWithPath(List.of("agent.root", "agent.other")), run(), depth,
                null, lease(), false, assets()).reasonCode()).isEqualTo("A2A_DEPTH_EXCEEDED");
    }

    private static AssetBundle assets() {
        StandardQaBank bank = new StandardQaBank();
        StandardQaBank.Entry entry = new StandardQaBank.Entry();
        entry.setId("qa.payroll.arrival");
        entry.setPatterns(List.of("工资为什么没到账"));
        entry.setAnswer("工资通常在发薪日当日到账");
        bank.setItems(List.of(entry));

        MenuCatalog menus = new MenuCatalog();
        MenuCatalog.MenuEntry menu = new MenuCatalog.MenuEntry();
        menu.setMenuId("menu.account.查询账户余额"); menu.setTechDomain("account");
        menu.setFinalName("查询账户余额"); menu.setPath("账户>查余额");
        menus.setMenus(List.of(menu));

        CapabilityCard nav = new CapabilityCard("cap.nav.account_查询账户余额", "打开余额菜单",
                Enums.CapabilityType.TOOL, Enums.Granularity.TOOL, "agent.finance", List.of("account"),
                "", List.of(), Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 1000,
                Enums.Idempotency.SUPPORTED, "owner", "1", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), List.of(), Enums.GuardrailOwner.DOMAIN, false,
                ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
        return new AssetBundle("v", "v", List.of(nav), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, bank, null, menus);
    }

    private static Run run() {
        return new Run("tenant", "loop", "agent.test", "session", "root", "trace",
                "工资为什么没到账", Status.RUNNING, 0, 4,
                List.of("cap.nav.account_查询账户余额"), Map.of(), null,
                Instant.now().plusSeconds(30), 0, Instant.now(), Instant.now());
    }

    private static RequestContext context() {
        return new RequestContext("trace", "session", "user", "tenant", "agent.test",
                "TEST", "", "", false);
    }

    private static RequestContext contextWithPath(List<String> path) {
        return new RequestContext("trace", "session", "user", "tenant", "agent.test",
                "TEST", "", "", false,
                new PrincipalState("principal", true, "AUTHENTICATED", "TEST"),
                new InvocationLineage("root", "parent", "source", path, Instant.now().plusSeconds(30)));
    }

    private static ContextLease lease() {
        return ContextLease.degraded("session", "goal", Instant.now().plusSeconds(30));
    }
}
