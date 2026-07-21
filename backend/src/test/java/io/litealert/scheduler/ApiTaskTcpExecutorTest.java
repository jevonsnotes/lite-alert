package io.litealert.scheduler;

import io.litealert.scheduler.domain.TcpTaskConfig;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP connectivity probe (design D4). Success path uses a real ephemeral {@link ServerSocket} on
 * loopback; failure paths hit a closed port (refused) and an unroutable address (timeout).
 */
class ApiTaskTcpExecutorTest {

    private final ApiTaskTcpExecutor executor = new ApiTaskTcpExecutor();

    @Test
    void connectingToOpenPortSucceeds() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            TcpTaskConfig cfg = tcp("127.0.0.1", server.getLocalPort());
            ApiTaskTcpExecutor.Result res = executor.execute(cfg);
            assertThat(res.connected()).isTrue();
            assertThat(res.error()).isNull();
        }
    }

    @Test
    void connectingToClosedPortFails() throws Exception {
        // find a free port, close it, then probe it -> connection refused
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        TcpTaskConfig cfg = tcp("127.0.0.1", port);
        assertThatThrownBy(() -> executor.execute(cfg))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void connectingToUnroutableHostTimesOut() {
        // 10.255.255.1 is typically unroutable on loopback-only test envs -> connect timeout
        TcpTaskConfig cfg = tcp("10.255.255.1", 80);
        cfg.setTimeouts(new io.litealert.scheduler.domain.TaskConfig.Timeouts(1, null, null));
        assertThatThrownBy(() -> executor.execute(cfg))
                .isInstanceOfAny(SocketTimeoutException.class, java.net.ConnectException.class);
    }

    @Test
    void unresolvedHostFailsWithUnknownHostException() {
        TcpTaskConfig cfg = tcp("nonexistent.invalid.domain.example", 80);
        assertThatThrownBy(() -> executor.execute(cfg))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    private TcpTaskConfig tcp(String host, int port) {
        TcpTaskConfig c = new TcpTaskConfig();
        c.setHost(host);
        c.setPort(port);
        return c;
    }
}
