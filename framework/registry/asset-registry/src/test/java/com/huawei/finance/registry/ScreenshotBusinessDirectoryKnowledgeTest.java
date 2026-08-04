package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.StandardQaBank;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ScreenshotBusinessDirectoryKnowledgeTest {

    private static final List<String> SCREENSHOT_QA_IDS = List.of(
            "qa.mobile-banking.business-directory.overview",
            "qa.mobile-banking.business-directory.account",
            "qa.mobile-banking.business-directory.transfer",
            "qa.mobile-banking.business-directory.payment",
            "qa.mobile-banking.business-directory.wealth",
            "qa.mobile-banking.business-directory.creditcard",
            "qa.mobile-banking.business-directory.loan",
            "qa.mobile-banking.business-directory.benefits",
            "qa.mobile-banking.business-directory.customer",
            "qa.mobile-banking.business-directory.branch",
            "qa.mobile-banking.business-directory.finance-assistant",
            "qa.branch.appointment.supported-businesses");

    @Test
    void loadsReviewedScreenshotKnowledgeWithNarrowPatterns() {
        var root = AssetLoader.locateAssets();
        var bundle = new AssetLoader(new ContractValidator()).load(root);
        Map<String, StandardQaBank.Entry> entries = bundle.standardQa().getItems().stream()
                .collect(Collectors.toMap(StandardQaBank.Entry::getId, entry -> entry));

        assertThat(entries).containsKeys(SCREENSHOT_QA_IDS.toArray(String[]::new));
        assertThat(SCREENSHOT_QA_IDS).allSatisfy(id -> {
            StandardQaBank.Entry entry = entries.get(id);
            assertThat(entry.getStatus()).isEqualTo(StandardQaBank.Entry.Status.APPROVED);
            assertThat(entry.getSourceRef()).startsWith("screenshotLedger:");
            assertThat(entry.getAnswer()).isNotBlank();
            assertThat(entry.getMenuOptions()).isNotEmpty();
            assertThat(entry.getMenuOptions()).allSatisfy(menuId -> {
                var menu = bundle.menus().find(menuId);
                assertThat(menu).as("%s 引用的菜单 %s", id, menuId).isPresent();
                assertThat(bundle.capability(AssetLoader.capabilityId(menu.orElseThrow())))
                        .as("%s 的导航能力", menuId).isNotNull();
            });
        });

        assertThat(entries.get("qa.mobile-banking.business-directory.overview").getMenuOptions())
                .hasSize(11);
        assertThat(entries.get("qa.branch.appointment.supported-businesses").getMenuOptions())
                .contains("menu.branch_service.预约存款")
                .doesNotContain("menu.deposit_service.存款");
        assertThat(bundle.menus().find("menu.branch_service.预约存款")).get()
                .satisfies(menu -> assertThat(AssetLoader.routeQuery(bundle.menus(), menu))
                        .isEqualTo("打开网点预约存款"));

        assertThat(bundle.standardQa().match("手机银行贷款服务包括哪些功能"))
                .get().extracting(StandardQaBank.Entry::getId)
                .isEqualTo("qa.mobile-banking.business-directory.loan");
        assertThat(bundle.standardQa().match("账户管理里有什么功能"))
                .get().extracting(StandardQaBank.Entry::getId)
                .isEqualTo("qa.mobile-banking.business-directory.account");
        assertThat(bundle.standardQa().match("网点预约支持哪些业务"))
                .get().extracting(StandardQaBank.Entry::getId)
                .isEqualTo("qa.branch.appointment.supported-businesses");

        assertThat(List.of("转账", "帮我转账", "存款", "我要存款", "查余额", "信用报告"))
                .allSatisfy(query -> assertThat(bundle.standardQa().match(query))
                        .as("裸执行或导航意图不能被目录知识截获：%s", query)
                        .isEmpty());
    }

    @Test
    void ledgerFilesExistAndMatchRecordedChecksums() throws Exception {
        var root = AssetLoader.locateAssets();
        var ledger = new ObjectMapper(new YAMLFactory()).readTree(
                Files.readAllBytes(root.resolve("knowledge/screenshot-source-ledger.yaml")));
        assertThat(ledger.path("sources")).hasSize(5);

        for (var source : ledger.path("sources")) {
            var evidence = root.resolve("knowledge").resolve(source.path("file").asText());
            assertThat(evidence).isRegularFile();
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidence)));
            assertThat(actual).isEqualTo(source.path("sha256").asText());
        }
    }
}
