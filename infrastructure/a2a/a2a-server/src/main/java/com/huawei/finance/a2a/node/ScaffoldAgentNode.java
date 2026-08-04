package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 占位节点：一个科技域的**身份与显式失败**（架构草案 v0.3 §5.4、阶段 3b）。
 *
 * <p>它交付的不是能力，是两件事:
 *
 * <ol>
 *   <li><b>身份</b>——这个域在目录里看得见。看不见的话域路由会当它不存在，
 *       用户得到的是「不支持」而不是「该业务暂未开放」,而这两句话的归因完全不同:
 *       前者会被当成需求缺失去排期，后者是已知的交付进度。
 *   <li><b>显式失败</b>——接到委托明确回 {@link DelegationOutcome#DOMAIN_NOT_OPEN}，
 *       不假成功、不回假数据。
 * </ol>
 *
 * <p><b>零能力域不在阶段 3b 建中控</b>（§12 第 17b 条）:占位节点没有任务表、没有护栏、
 * 没有 Intent 资产。给二十来个空域各建一套任务表，换来的是二十来套没有业务在跑的
 * 状态机——升级触发条件是「本域第一张能力卡发布」，不是排期到了。
 *
 * <p>回的是 DOMAIN_NOT_OPEN 而不是 NOT_MINE:这件事**属于**本域，只是本域还没建成。
 * 回 NOT_MINE 会让入口改投一次，白花一次委托预算去问另一个更不相关的域。
 */
public class ScaffoldAgentNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(ScaffoldAgentNode.class);

    private final String agentId;
    private final String techDomainCode;

    public ScaffoldAgentNode(String agentId, String techDomainCode) {
        this.agentId = agentId;
        this.techDomainCode = techDomainCode;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    /**
     * 占位节点不自治。
     *
     * <p>它没有域内规划器，接 GOAL 就只能猜。声明为非自治之后，
     * 网关在投递前就拒了 GOAL——比投过来再回一句 DOMAIN_NOT_OPEN 少一跳，
     * 且原因码更准（{@code GOAL_TO_NON_AUTONOMOUS} 而不是「域没建成」）。
     */
    @Override
    public boolean autonomous() {
        return false;
    }

    @Override
    public DelegationReceipt handle(DelegationEnvelope envelope) {
        log.info("占位节点收到委托，回显式未开放 delegation={} domain={} capability={}",
                envelope.delegationId(), techDomainCode, envelope.capabilityId());

        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION,
                envelope.delegationId(), DelegationOutcome.DOMAIN_NOT_OPEN,
                // 事实必须为空。给一个「假装办了」的字段就是假数据，
                // 而假数据在面客链路上和真数据长得一模一样
                Map.of(), List.of(), "DOMAIN_NOT_OPEN",
                "科技域 " + techDomainCode + " 尚未交付为 AgentNode");
    }

    public String techDomainCode() {
        return techDomainCode;
    }
}
