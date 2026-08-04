package com.huawei.finance.oj.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.gateway.ToolChatReply;
import com.huawei.finance.gateway.ToolChatRequest;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 把模型网关注册成 OJ provider 之后，OJ 侧的调用必须真的走这条通道。
 *
 * <p>这套用例守的不是「能调通」，而是**流量的去向**：OJ 若用它自带的直连客户端，
 * 一样能调通，只是往返预算、熔断与审计全部落空，而且不报错。
 */
class ModelGatewayProviderTest {

    private static ModelRequestConfig requestConfig(String model) {
        ModelRequestConfig config = new ModelRequestConfig();
        config.setModelName(model);
        return config;
    }

    private static ModelClientConfig clientConfig() {
        // 凭据是占位值，真密钥只在网关实现里；OJ 的配置对象强制这两项非空，绕不开
        return ModelGatewayClientFactory.clientConfig();
    }

    private static BaseModelClient client(RecordingGateway gateway) {
        ModelGatewayClientFactory.bind(gateway);
        return new ModelGatewayClientFactory().create(requestConfig("Qwen/Qwen3-30B"), clientConfig());
    }

    @Test
    @DisplayName("ServiceLoader 能发现本工厂，OJ 才认得 agent-platform 这个 provider")
    void factoryIsDiscoverableSoOjCanResolveTheProvider() {
        List<String> providers = ServiceLoader.load(Model.ModelClientFactory.class).stream()
                .map(p -> p.get().providerName())
                .toList();
        assertThat(providers)
                .as("services 文件漏了或被 fat jar 覆盖时，OJ 会静默回落到直连客户端，"
                        + "预算与熔断随之失效且不报错")
                .contains(ModelGatewayClientFactory.PROVIDER);
    }

    @Test
    @DisplayName("OJ 的调用落到网关，而不是自己发 HTTP")
    void invokeGoesThroughTheGateway() {
        RecordingGateway gateway = new RecordingGateway();
        BaseModelClient client = client(gateway);

        AssistantMessage reply = invoke(client, gateway, Map.of());

        assertThat(reply.getContentAsString()).isEqualTo("好的");
        assertThat(gateway.chats).hasSize(1);
        assertThat(gateway.chats.get(0).userPrompt()).isEqualTo("查一下余额");
        assertThat(gateway.chats.get(0).systemPrompt()).isEqualTo("你是助手");
    }

    @Test
    @DisplayName("未绑定网关时当场抛错，不回落到别的通道")
    void unboundFactoryFailsLoudly() {
        ModelGatewayClientFactory.bind(null);
        assertThatThrownBy(() -> new ModelGatewayClientFactory().create(requestConfig("m"), clientConfig()))
                .as("静默回落意味着请求照常成功、账单照常产生，只是没人在管预算")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("网关尚未绑定");
    }

    @Test
    @DisplayName("网关不可用转成异常，不返回空回复")
    void unavailableGatewayBecomesAnException() {
        RecordingGateway gateway = new RecordingGateway(false, "");
        BaseModelClient client = client(gateway);

        assertThatThrownBy(() -> invoke(client, gateway, Map.of()))
                .as("返回空 AssistantMessage 会让 Agent 把「模型没答」当成「模型答了个空」继续往下走")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    @DisplayName("response_format 透传成 jsonMode，否则仲裁会拿回一段自然语言")
    void jsonModeIsCarriedThrough() {
        RecordingGateway gateway = new RecordingGateway();
        BaseModelClient client = client(gateway);

        invoke(client, gateway, Map.of("response_format", Map.of("type", "json_object")));

        assertThat(gateway.chats.get(0).jsonMode()).isTrue();
    }

    @Test
    @DisplayName("带工具的调用走 Agent 通道，不走仲裁那条单轮定参的路")
    void toolCallsGoThroughTheAgentChannel() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.nextToolCalls = List.of(
                new ToolChatReply.ToolCallRequest("call-1", "cap.transfer", "{\"payee\":\"老徐\"}"));
        BaseModelClient client = client(gateway);

        AssistantMessage reply = client.invoke(
                List.of(new SystemMessage("你是规划器"), new UserMessage("给老徐转 1000")),
                List.of(ToolInfo.builder().name("cap.transfer").description("转账")
                        .parameters(Map.of("type", "object")).build()),
                0f, 1f, "m", 512, null, null, null, Map.of());

        assertThat(gateway.chats)
                .as("仲裁那条路是单轮定参、计入 A 线预算的，Agent 循环不能借道")
                .isEmpty();
        assertThat(gateway.toolChats).hasSize(1);
        assertThat(gateway.toolChats.get(0).tools()).hasSize(1);
        assertThat(reply.getToolCalls()).extracting(ToolCall::getName).containsExactly("cap.transfer");
    }

    @Test
    @DisplayName("OJ 传入零输出上限时适配器补成合法正数")
    void zeroMaxTokensIsNormalizedAtTheGatewayBoundary() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        BaseModelClient client = client(gateway);

        client.invoke(
                List.of(new SystemMessage("你是规划器"), new UserMessage("查余额")),
                List.of(ToolInfo.builder().name("cap_balance").description("余额")
                        .parameters(Map.of("type", "object")).build()),
                0f, 1f, "m", 0, null, null, null, Map.of());

        assertThat(gateway.toolChats).singleElement()
                .extracting(ToolChatRequest::maxTokens)
                .isEqualTo(1024);
    }

    @Test
    @DisplayName("消息按原顺序摊平，不按角色拼接")
    void agentMessagesKeepTheirOrder() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        BaseModelClient client = client(gateway);

        client.invoke(
                List.of(new SystemMessage("你是规划器"),
                        new UserMessage("给老徐转 1000"),
                        new ToolMessage("已列入计划", "call-1")),
                List.of(ToolInfo.builder().name("cap.transfer").build()),
                0f, 1f, "m", 512, null, null, null, Map.of());

        assertThat(gateway.toolChats.get(0).messages())
                .as("工具结果与助手消息必须交替出现，顺序错了模型会反复调同一个工具")
                .extracting(ToolChatRequest.ChatMessage::role)
                .containsExactly("system", "user", "tool");
        assertThat(gateway.toolChats.get(0).messages().get(2).toolCallId()).isEqualTo("call-1");
    }

    @Test
    @DisplayName("第二轮请求仍带上一轮 assistant.tool_calls，多轮 ReAct 才接得上")
    void secondRoundKeepsPriorAssistantToolCalls() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        BaseModelClient client = client(gateway);

        AssistantMessage prior = new AssistantMessage("");
        prior.setToolCalls(List.of(ToolCall.builder()
                .id("call-1")
                .type("function")
                .name("cap.balance")
                .arguments("{}")
                .build()));

        client.invoke(
                List.of(new SystemMessage("你是规划器"),
                        new UserMessage("查余额再转账"),
                        prior,
                        new ToolMessage("已列入计划", "call-1")),
                List.of(ToolInfo.builder().name("cap.transfer").build()),
                0f, 1f, "m", 512, null, null, null, Map.of());

        List<ToolChatRequest.ChatMessage> messages = gateway.toolChats.get(0).messages();
        assertThat(messages).extracting(ToolChatRequest.ChatMessage::role)
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(messages.get(2).toolCalls())
                .as("摊平时丢掉 tool_calls，第二轮协议就不完整")
                .extracting(ToolChatRequest.ChatMessage.ToolCall::name)
                .containsExactly("cap.balance");
        assertThat(messages.get(2).toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(messages.get(3).toolCallId()).isEqualTo("call-1");
    }

    @Nested
    @DisplayName("多模态")
    class Multimodal {

        @Test
        @DisplayName("一律抛错而不是返回空响应")
        void failsInsteadOfReturningEmpty() {
            BaseModelClient client = client(new RecordingGateway());
            assertThatThrownBy(() -> client.generateImage(List.of(new UserMessage("画一张")),
                    "m", "1024x1024", null, 1, false, false, 0, Map.of()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static AssistantMessage invoke(BaseModelClient client, RecordingGateway gateway,
                                           Map<String, Object> kwargs) {
        try {
            return client.invoke(
                    List.of(new SystemMessage("你是助手"), new UserMessage("查一下余额")),
                    null, 0f, 1f, "Qwen/Qwen3-30B", 512, null, null, null, kwargs);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
