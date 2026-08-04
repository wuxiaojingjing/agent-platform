package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.registry.asset.SyntacticTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 句法模版（FP-1I）。
 *
 * <p>这一组用例守的是**匹配范围**，不是「能不能匹配上」。标准答案命中即直出，
 * 不召回也不过模型，所以模版宽一点的后果不是判得差一点，而是一整片流量再也到不了正常链路。
 */
class SyntacticTemplateTest {

    @Test
    @DisplayName("择一与可省：一条模版顶掉一批穷举写法")
    void alternativesAndOptional() {
        SyntacticTemplate template = SyntacticTemplate.compile("[请问]{余额|存款}怎么{查|看}");

        assertThat(template.matches("余额怎么查")).isTrue();
        assertThat(template.matches("请问存款怎么看")).isTrue();
        assertThat(template.matches("余额怎么办")).isFalse();
    }

    @Test
    @DisplayName("整句匹配：片段命中不算命中")
    void anchoredToWholeSentence() {
        SyntacticTemplate template = SyntacticTemplate.compile("转账手续费怎么算");

        assertThat(template.matches("转账手续费怎么算")).isTrue();
        assertThat(template.matches("  转账手续费怎么算  ")).as("两头空白不算差别").isTrue();
        assertThat(template.matches("我不想知道转账手续费怎么算"))
                .as("片段匹配下这句会命中，而它恰恰是否定的意思")
                .isFalse();
    }

    @Test
    @DisplayName("通配符有长度上限，不许它吃掉整句话")
    void wildcardIsBounded() {
        SyntacticTemplate template = SyntacticTemplate.compile("转账*手续费怎么算");

        assertThat(template.matches("转账的手续费怎么算")).isTrue();
        assertThat(template.matches("转账" + "啊".repeat(50) + "手续费怎么算"))
                .as("中间塞五十个字还算同一个问题的话，这条模版已经不是在描述问题了")
                .isFalse();
    }

    @Test
    @DisplayName("过宽的模版编译期就拒绝，不留到线上靠人发现")
    void overlyBroadTemplateIsRejected() {
        assertThatThrownBy(() -> SyntacticTemplate.compile("*余*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过宽");
        assertThatThrownBy(() -> SyntacticTemplate.compile("*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("正则元字符按字面处理，模版语言里没有正则")
    void regexMetaCharactersAreLiteral() {
        SyntacticTemplate template = SyntacticTemplate.compile("手续费是多少.元");

        assertThat(template.matches("手续费是多少.元")).isTrue();
        assertThat(template.matches("手续费是多少2元"))
                .as("点号若被当成正则的任意字符，这句会命中")
                .isFalse();
    }

    @Test
    @DisplayName("记号写错当场报错，不静默降级成字面匹配")
    void malformedMarkersFailFast() {
        assertThatThrownBy(() -> SyntacticTemplate.compile("{余额|存款怎么查"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有配对");
        assertThatThrownBy(() -> SyntacticTemplate.compile("{余额|}怎么查"))
                .as("空分支等价于可省，那是 [] 的职责，两种写法同义会让审模版的人记两套")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntacticTemplate.compile("{余额}怎么查"))
                .as("只有一个分支的择一多半是手误")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("择一里的字数不顶过宽判定的额度")
    void alternativesDoNotInflateLiteralCount() {
        assertThatThrownBy(() -> SyntacticTemplate.compile("{查询余额|看一下存款}*"))
                .as("按分支总字数算，这条能轻松过关，可它的约束只有一个位置")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过宽");
    }
}
