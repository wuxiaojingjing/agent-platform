package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import java.util.Set;

/**
 * 槽位名的权威出处（FP-45）。
 *
 * <p>此前槽位名有三个各自独立的来源：抽槽器里的字符串字面量、能力卡的 {@code requiredSlots}、
 * 澄清话术的键。三处手写、互不校验，于是「漂移」不是假想风险——§2.7.3 第 3 项就是一个上线
 * 系统里的实例：NLU 给出的是 {@code remittanceBankName}，工具实际接收的是
 * {@code remittancePlatformName}，两边都合法、都通过各自的校验，用户看到的是转账失败。
 *
 * <p>这个类不改变任何运行期行为，它的用途是让那三处**有一个共同的比对基准**，
 * 使 {@code AssetLint} 能在发布前判定「这张卡声明了一个没人抽得出来的槽位」。
 *
 * <p>加新槽位的顺序是固定的：先在这里加常量，再改抽槽器，再配澄清话术，最后才是能力卡声明。
 * 反过来做——先在能力卡里声明——得到的是一个永远填不上、因而永远在澄清的能力。
 */
@Api
public final class SlotNames {

    /** 收款人。抽取只做候选，未链接到通讯录实体时留空走澄清（FP-1F）。 */
    public static final String PAYEE = "payee";

    /** 金额。禁止用模型解析，要求同输入同输出（实施架构 §2.7）。 */
    public static final String AMOUNT = "amount";

    /** 卡种（CREDIT / DEBIT）。换卡的路由必需信息，不是执行细节。 */
    public static final String CARD_TYPE = "cardType";

    /** 卡号，已去掉复制粘贴带进来的空格与短横线。 */
    public static final String CARD_NUMBER = "cardNumber";

    /** Opaque reference to a card selected from trusted context. */
    public static final String CARD_REF = "cardRef";

    /** 日期。相对表述（「上周三」）不靠正则穷举，由仲裁 prompt 的日期表解决（§2.7.6）。 */
    public static final String DATE = "date";

    /**
     * 付款账户。可由「第二张卡」等工作记忆指代解析填入，不要求用户复述卡号。
     *
     * <p>转账卡不把它放进 requiredSlots：缺了也能确认（Mock 有默认出账账户），
     * 有了则确认页与执行回显用解析后的别名。
     */
    public static final String FROM_ACCOUNT = "fromAccount";

    /** Model-resolved ordinal into the latest ordered account list. */
    public static final String ACCOUNT_ORDINAL = "accountOrdinal";

    /** Calculation basis; a balance-derived amount must be recomputed from authoritative data. */
    public static final String AMOUNT_BASIS = "amountBasis";

    /**
     * 主 Agent 能抽出的槽位全集。
     *
     * <p>能力卡的 {@code requiredSlots} 必须是它的子集：声明了这里没有的名字，
     * 意味着主 Agent 永远填不上它，而 R2 能力缺槽即拒绝执行——用户会得到一个死循环的澄清。
     */
    public static final Set<String> EXTRACTABLE =
            Set.of(PAYEE, AMOUNT, CARD_TYPE, CARD_NUMBER, CARD_REF, DATE, FROM_ACCOUNT,
                    ACCOUNT_ORDINAL, AMOUNT_BASIS);

    private SlotNames() {
    }
}
