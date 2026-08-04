package com.huawei.finance.contracts.port;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.stability.Spi;
import java.util.List;
import java.util.Optional;

/**
 * 资产目录只读视图（架构草案阶段 0）。
 *
 * <p>意图引擎对「能力卡与版本」的稳定读取面。融合配置、强规则、同义词等仍可暂时经
 * {@code AssetBundle} 访问；本接口先钉住最常用、也最该与检索解耦的那一层，避免一次搬家。
 */
@Spi
public interface AssetCatalog {

    /** 资产版本（人工版本 + 内容摘要），参与缓存键。 */
    String assetVersion();

    /** 全部能力卡。 */
    List<CapabilityCard> capabilities();

    /** 按 id 查卡。 */
    Optional<CapabilityCard> findCapability(String capabilityId);
}
