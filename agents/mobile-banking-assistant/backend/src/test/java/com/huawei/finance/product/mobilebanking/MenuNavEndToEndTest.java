package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponseComponent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 菜单跳转切片：打开理财交易记录 → OPEN_MENU。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MenuNavEndToEndTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过菜单跳转端到端");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过菜单跳转端到端");
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[8];
            int read = socket.getInputStream().read(buffer);
            return read > 0 && new String(buffer, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean postgresAccepts() {
        try (Connection ignored = DriverManager.getConnection(PG_URL, "agent_platform", "agent_platform")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeEach
    void clearDecisionCache() {
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("打开理财交易记录 → NAVIGATION + tpl.nav.open + OPEN_MENU")
    void openWealthTradeRecordMenu() {
        ChatRequestDto request = new ChatRequestDto(
                "s-" + UUID.randomUUID(), "u-1", "打开理财交易记录", "MOBILE_BANK", "home", "");
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.decision().decision()).isEqualTo(Decision.NAVIGATION);
        assertThat(body.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(body.plan().templateKey()).isEqualTo("tpl.nav.open");
        assertThat(body.plan().actionCodes()).contains("OPEN_MENU");
        assertThat(body.plan().cardComponents()).contains(ResponseComponent.NAVIGATION);
        assertThat(body.text()).contains("理财交易记录");
        assertThat(body.plan().slots()).containsEntry("action", "OPEN_MENU");
    }

    @Test
    @DisplayName("截图知识菜单可作为下一轮输入进入 NAVIGATION")
    void openScreenshotKnowledgeMenu() {
        ChatRequestDto request = new ChatRequestDto(
                "s-" + UUID.randomUUID(), "u-1", "打开信用报告", "MOBILE_BANK", "home", "");
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.decision().decision()).isEqualTo(Decision.NAVIGATION);
        assertThat(body.decision().selectedCandidateId())
                .isEqualTo("cap.nav.personal_info_信用报告");
        assertThat(body.plan().templateKey()).isEqualTo("tpl.nav.open");
        assertThat(body.plan().slots()).containsEntry("menuId", "menu.personal_info.信用报告");
        assertThat(body.plan().actionCodes()).contains("OPEN_MENU");
        assertThat(body.text()).contains("信用报告").doesNotContain("暂时无法完成");
    }
}
