package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class XiaoiKnowledgeMigrationTest {
    @Test
    void importsApprovedAnswersAndSafeBlockedGuidanceAndKeepsAllTwentyOneLedgerRows() throws Exception {
        var root = AssetLoader.locateAssets();
        var bundle = new AssetLoader(new ContractValidator()).load(root);
        var xiaoiItems = bundle.standardQa().getItems().stream()
                .filter(entry -> entry.getId().startsWith("qa.transfer.error.")
                        || entry.getId().startsWith("qa.card.")
                        || entry.getId().startsWith("qa.passbook."))
                .toList();
        assertThat(xiaoiItems).hasSize(7);
        assertThat(xiaoiItems.stream()
                .filter(entry -> entry.getStatus()
                        == com.huawei.finance.registry.asset.StandardQaBank.Entry.Status.APPROVED))
                .hasSize(6);
        assertThat(bundle.standardQa().match("遇到26023怎么办")).isPresent();
        assertThat(bundle.standardQa().match("什么是换卡无忧")).isPresent();
        assertThat(bundle.standardQa().match("遇到7845怎么办")).isEmpty();
        assertThat(bundle.standardQa().match("可以原号补办吗")).get()
                .extracting(com.huawei.finance.registry.asset.StandardQaBank.Entry::getStatus)
                .isEqualTo(com.huawei.finance.registry.asset.StandardQaBank.Entry.Status.BLOCKED_SOURCE_REVIEW);

        var ledger = new ObjectMapper(new YAMLFactory()).readTree(
                Files.readAllBytes(root.resolve("xiaoi/migration-ledger.yaml")));
        assertThat(ledger.path("items").size()).isEqualTo(21);
        assertThat(ledger.path("items").findValuesAsText("lineage"))
                .hasSize(21)
                .allSatisfy(lineage -> assertThat(lineage)
                        .startsWith("reviewed-workbook:standard-question:"));
        assertThat(ledger.path("items").findValuesAsText("migrationStage"))
                .contains("INTERNALIZED_BATCH_1", "BLOCKED_SOURCE_REVIEW", "BLOCKED_ANSWER_APPROVAL");
    }
}
