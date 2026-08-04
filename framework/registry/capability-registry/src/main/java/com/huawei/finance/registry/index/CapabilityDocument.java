package com.huawei.finance.registry.index;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.contracts.model.CapabilityCard;
import java.util.ArrayList;
import java.util.List;

/**
 * 能力卡的检索视图。
 *
 * <p>与 {@link CapabilityCard} 分开：能力卡是治理契约，索引文档是检索结构。
 * 两者的演进节奏不同——加一个检索字段不该动契约，改一个契约字段也不该被索引映射绑住。
 *
 * @param capabilityId   能力标识
 * @param name           能力名称，BM25 侧加权最高
 * @param description    描述
 * @param searchText     名称、意图、示例问法、关键词拼成的检索正文
 * @param keywords       精确匹配词
 * @param domains        所属领域
 * @param capabilityType 能力类型
 * @param riskLevel      风险等级
 * @param requiredSlots  必填槽位
 * @param version        能力卡版本
 * @param vector         文档侧向量，模型不可用时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityDocument(
        String capabilityId,
        String name,
        String description,
        String searchText,
        List<String> keywords,
        List<String> domains,
        String capabilityType,
        String riskLevel,
        List<String> requiredSlots,
        String version,
        float[] vector) {

    public static CapabilityDocument from(CapabilityCard card, float[] vector) {
        List<String> parts = new ArrayList<>();
        parts.add(card.name());
        if (card.description() != null) {
            parts.add(card.description());
        }
        parts.addAll(card.supportedIntents());
        parts.addAll(card.utterances());
        parts.addAll(card.keywords());

        return new CapabilityDocument(
                card.capabilityId(),
                card.name(),
                card.description(),
                String.join(" ", parts),
                card.keywords(),
                card.domains(),
                card.type().name(),
                card.riskLevel().name(),
                card.requiredSlots(),
                card.version(),
                vector);
    }
}
