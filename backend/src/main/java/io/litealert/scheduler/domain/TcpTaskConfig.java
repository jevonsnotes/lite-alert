package io.litealert.scheduler.domain;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Persisted configuration for a TCP-type scheduled task (design D1). A connectivity probe
 * (形态 A): the engine attempts a TCP connect to {@code host}:{@code port}; success = connected,
 * failure = refused / timeout / unresolved host. No payload is sent, no response is read, no
 * response assertion applies (see {@code tcp-task-runner} spec).
 *
 * <p>{@link Timeouts#getConnect()} is the connect timeout (read/write ignored for TCP).
 */
@JsonTypeName("TCP")
public class TcpTaskConfig extends TaskConfig {

    /** Target host (hostname or IP). */
    private String host;
    /** Target port, 1-65535. */
    private Integer port;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
}
