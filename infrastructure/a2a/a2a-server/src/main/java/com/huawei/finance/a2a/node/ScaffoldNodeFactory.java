package com.huawei.finance.a2a.node;

import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.contracts.a2a.AgentNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为还没有真节点的科技域补占位节点（架构草案 v0.3 阶段 3b）。
 *
 * <p><b>只补，绝不覆盖。</b>已交付的域必须由它自己的实现接管——
 * 反过来的话，某个域的真节点因为装配顺序被占位节点挡住，表现是这个域忽然「未开放」,
 * 而代码明明在仓库里。这类故障查起来极慢，因为没有任何一处报错。
 *
 * <p>补齐的依据是**卡上声明的域**而不是 agentId:{@code agent.creditcard} 的域码是
 * {@code creditcard_service}，靠 agentId 减前缀会给这个域补一个多余的占位节点，
 * 于是同一个域有两个可发现节点——正是双射门禁要拦的那种不确定性。
 */
public final class ScaffoldNodeFactory {

    private static final Logger log = LoggerFactory.getLogger(ScaffoldNodeFactory.class);

    private ScaffoldNodeFactory() {
    }

    /**
     * @param cards 全部节点卡（含未交付的）
     * @param realNodes 已交付的真节点
     * @return 只包含需要补的占位节点，不含 {@code realNodes} 里已有的
     */
    public static List<AgentNode> fillGaps(List<AgentCard> cards, List<AgentNode> realNodes) {
        Set<String> taken = new LinkedHashSet<>();
        realNodes.forEach(n -> taken.add(n.agentId()));

        List<AgentNode> scaffolds = new ArrayList<>();
        for (AgentCard card : cards) {
            if (taken.contains(card.agentId())) {
                // 卡说 SCAFFOLD 但真节点已经在了：以真节点为准，只提醒卡该更新。
                // 反过来「以卡为准」会把一个能用的域按未开放对待
                if (!card.deliverable()) {
                    log.info("卡标记为未交付但真节点已注册，以真节点为准 agent={}", card.agentId());
                }
                continue;
            }
            scaffolds.add(new ScaffoldAgentNode(card.agentId(), card.techDomainCode()));
        }
        log.info("占位节点补齐 真节点={} 占位={} 合计={}",
                taken.size(), scaffolds.size(), taken.size() + scaffolds.size());
        return scaffolds;
    }
}
