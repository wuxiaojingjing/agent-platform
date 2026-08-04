package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.runtime.spi.SessionAffinityPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * FP-66 实例亲和（ADR-004）。
 *
 * <p>打真 Redis：替身测不出 setIfAbsent 竞态与 TTL 续期。
 */
class SessionAffinityTest {

    private RedissonClient redisson;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过会话亲和测试");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379").setTimeout(2000);
        redisson = Redisson.create(config);
        meters = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("同实例 claim / assertOwner 通过")
    void sameInstanceOwnsTheSession() {
        SessionAffinity a = new SessionAffinity(redisson, meters, "inst-a");
        String session = "aff-" + UUID.randomUUID();

        assertThat(a.claim("agent.entry", "-", session)).isEqualTo(SessionAffinityPort.Outcome.OK);
        assertThat(a.assertOwner("agent.entry", "-", session)).isEqualTo(SessionAffinityPort.Outcome.OK);

        a.release("agent.entry", "-", session);
    }

    @Test
    @DisplayName("他实例续办得到 MISMATCH 并打点")
    void otherInstanceMismatches() {
        SessionAffinity owner = new SessionAffinity(redisson, meters, "inst-owner");
        SessionAffinity other = new SessionAffinity(redisson, meters, "inst-other");
        String session = "aff-" + UUID.randomUUID();

        assertThat(owner.claim("agent.entry", "-", session)).isEqualTo(SessionAffinityPort.Outcome.OK);
        assertThat(other.assertOwner("agent.entry", "-", session)).isEqualTo(SessionAffinityPort.Outcome.MISMATCH);
        assertThat(meters.counter("huawei.finance.agent.session.affinity.mismatch").count()).isGreaterThan(0);

        owner.release("agent.entry", "-", session);
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 500);
            socket.setSoTimeout(500);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buf = new byte[7];
            int read = socket.getInputStream().read(buf);
            return read > 0 && new String(buf, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }
}
