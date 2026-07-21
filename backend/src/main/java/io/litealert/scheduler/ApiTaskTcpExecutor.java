package io.litealert.scheduler;

import io.litealert.scheduler.domain.TaskConfig;
import io.litealert.scheduler.domain.TcpTaskConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Executes a TCP-type scheduled task's connectivity probe (design D4). Opens a TCP connection to
 * {@code host:port}; {@link #execute} returns success the instant the connection is established,
 * then closes it immediately. No data is sent, no response is read - this is a pure connectivity
 * probe (形态 A). Connect timeout comes from {@link TaskConfig.Timeouts#getConnect()} (default 5s).
 *
 * <p>Each probe uses a fresh {@link Socket} via try-with-resources; the probe is low-frequency
 * (Cron-driven) and short-lived, so a connection pool buys nothing.
 */
@Slf4j
@Component
public class ApiTaskTcpExecutor {

    public Result execute(TcpTaskConfig config) throws Exception {
        int connectSeconds = effectiveConnect(config);
        int connectMillis = connectSeconds <= 0 ? 0 : (int) Duration.ofSeconds(connectSeconds).toMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), connectMillis);
            return new Result(true, null);
        }
    }

    /** Resolve the connect timeout from the (base-class) timeouts, defaulting when null. */
    private int effectiveConnect(TcpTaskConfig config) {
        TaskConfig.Timeouts t = config.getTimeouts();
        return t == null ? TaskConfig.Timeouts.DEFAULT_CONNECT : t.effectiveConnect();
    }

    public record Result(boolean connected, String error) {}
}
