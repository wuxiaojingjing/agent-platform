package com.huawei.finance.product.mobilebanking.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.AssetStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptOptimizationViewTest {

    @TempDir
    Path workspace;

    @Test
    void exposesFrozenInputsAndReviewOnlyCandidate() throws Exception {
        AssetStore store = new AssetStore(
                new AssetLoader(new ContractValidator()), AgentAssetLocations.requireAssets());
        String assetVersion = store.current().assetVersion();
        Path eval = Files.createDirectories(workspace.resolve("eval"));
        Path output = Files.createDirectories(workspace.resolve("out"));
        new ObjectMapper().writeValue(eval.resolve("trajectories.json").toFile(), List.of(Map.of(
                "caseId", "replace-card",
                "query", "换卡",
                "assetVersion", assetVersion,
                "truth", Map.of("decision", "CLARIFY"),
                "userPrompt", "候选能力：cap.card.replace\n用户输入：换卡")));
        new ObjectMapper().writeValue(eval.resolve("context-rewrite-trajectories.json").toFile(),
                List.of(Map.of(
                        "caseId", "second-account",
                        "query", "第二张呢",
                        "assetVersion", "stale-version",
                        "truth", Map.of("resolutionType", "ORDINAL_REFERENCE"),
                        "conversationHistory", List.of(
                                Map.of("role", "user", "content", "查一下余额"),
                                Map.of("role", "assistant", "content", "BalanceQuery()"),
                                Map.of("role", "tool", "content", Map.of("balance", "100"))),
                        "userPrompt", "conversationHistory=[第一轮]\noriginalQuery=第二张呢")));
        Files.writeString(output.resolve("arbitration-skill.candidate.yaml"), """
                # 现状：通过 0/1
                # 候选：通过 1/1
                # 轨迹资产版本：%s
                version: "route-shape-v4（待人工升位）"
                system: |
                  候选提示词
                """.formatted(assetVersion));

        PromptOptimizationView.Snapshot snapshot = new PromptOptimizationView(
                store, workspace, List.of(output)).snapshot();

        PromptOptimizationView.Mode arbitration = snapshot.modes().stream()
                .filter(mode -> mode.id().equals("arbitration")).findFirst().orElseThrow();
        assertThat(arbitration.trajectoryCount()).isEqualTo(1);
        assertThat(arbitration.trajectoriesStale()).isFalse();
        assertThat(arbitration.trajectories().getFirst().modelInput())
                .contains("候选能力", "用户输入：换卡");
        assertThat(arbitration.candidate().status()).isEqualTo("REVIEW_PENDING");
        assertThat(arbitration.candidate().baseline()).isEqualTo("通过 0/1");
        assertThat(arbitration.candidate().score()).isEqualTo("通过 1/1");
        assertThat(arbitration.candidate().prompt()).isEqualTo("候选提示词\n");

        PromptOptimizationView.Mode context = snapshot.modes().stream()
                .filter(mode -> mode.id().equals("context-rewrite")).findFirst().orElseThrow();
        assertThat(context.trajectoriesStale()).isTrue();
        assertThat(context.trajectories().getFirst().conversationHistory()).hasSize(3);
        assertThat(context.candidate().available()).isFalse();
        assertThat(snapshot.runAvailable()).isFalse();
    }
}
