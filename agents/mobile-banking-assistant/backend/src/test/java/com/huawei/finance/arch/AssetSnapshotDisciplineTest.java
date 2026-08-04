package com.huawei.finance.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.response.ResponsePlanner;
import com.huawei.finance.response.TemplateRenderer;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 请求链路上的组件不得把资产或由资产装出来的对象长期持有。
 *
 * <p>持有一份就等于锁死在启动那一刻的资产上：改了阈值页面显示已生效，实际判定却没变，
 * 而这件事不报错——它只是「怎么改都不起作用」，排查时几乎不会有人想到是持有引用的问题。
 *
 * <p>唯一的取用方式是在请求开头向 {@code EngineRegistry} 取一次快照。这条规矩靠人记不住，
 * 因为把 {@code AssetBundle} 加进构造函数是最自然的写法，且当场就能跑通。
 */
class AssetSnapshotDisciplineTest {

    private static JavaClasses appClasses;

    @BeforeAll
    static void importClasses() {
        appClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.finance.product.mobilebanking");
    }

    @Test
    @DisplayName("mobile-banking-assistant 里除了快照本身，谁都不许持有资产或由资产装出来的组件")
    void noLongLivedAssetReferences() {
        noFields()
                .that().areDeclaredInClassesThat()
                .resideOutsideOfPackage("com.huawei.finance.product.mobilebanking.console..")
                .should().haveRawType(AssetBundle.class)
                .orShould().haveRawType(IntentEngine.class)
                .orShould().haveRawType(ResponsePlanner.class)
                .orShould().haveRawType(TemplateRenderer.class)
                .because("这些对象与某一版资产绑定。请求链路要在开头向 EngineRegistry 取一次快照，"
                        + "持有字段会让实例永远停在启动时那份资产上，且热重载对它无效而不报错")
                .check(appClasses);
    }
}
