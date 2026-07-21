package io.litealert.scheduler;

import io.litealert.common.audit.AuditLogger;
import io.litealert.common.util.IdGenerator;
import io.litealert.notify.channel.WebhookResponseAssertor;
import io.litealert.notify.channel.WebhookResponseAssertor.Condition;
import io.litealert.notify.channel.WebhookResponseAssertor.Logic;
import io.litealert.notify.channel.WebhookResponseAssertor.Operator;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskCall;
import io.litealert.scheduler.domain.SchedulerTaskCallStore;
import io.litealert.scheduler.domain.SchedulerTaskStore;
import io.litealert.scheduler.domain.TaskConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * In-memory scheduler for published {@link SchedulerTask}s (design D1). Each published task is
 * scheduled via a {@link CronExpression} on a {@link ThreadPoolTaskScheduler}; publishing a task
 * cancels its old future (without interrupting an in-flight run) and reschedules from the new
 * published config. Startup rebuilds schedules for all PUBLISHED+enabled tasks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerEngine {

    private final SchedulerTaskStore taskStore;
    private final SchedulerTaskCallStore callStore;
    private final ApiTaskHttpExecutor httpExecutor;
    private final ApiTaskTcpExecutor tcpExecutor;
    private final TaskTargetGuard guard;
    private final WebhookResponseAssertor assertor;
    private final AuditLogger audit;
    private final SchedulerNotifier notifier;
    private final io.litealert.scheduler.domain.SchedulerNotifyConfigStore notifyConfigStore;

    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void init() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("lite-alert-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        if (!taskStore.tableReady()) {
            log.warn("scheduler task table not ready; skip startup recovery");
            return;
        }
        int restored = 0;
        for (SchedulerTask t : taskStore.findSchedulable()) {
            if (schedule(t)) restored++;
        }
        log.info("scheduler engine started; restored {} published tasks", restored);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) scheduler.shutdown();
    }

    /** (Re)schedule a task from its published config. Called on publish / enable. */
    public synchronized void reschedule(String taskId) {
        cancelQuietly(taskId);
        taskStore.findById(taskId).ifPresent(this::schedule);
    }

    /** Remove a task's schedule (disable / delete). Does not interrupt an in-flight run. */
    public synchronized void unschedule(String taskId) {
        cancelQuietly(taskId);
    }

    private boolean schedule(SchedulerTask task) {
        if (!task.isSchedulable()) {
            return false;
        }
        // Authoritative cron is the published snapshot (publishedConfig.meta.cron); fall back to the
        // row-level cron for tasks published before the meta snapshot existed. This prevents an
        // unpublished draft cron from leaking into the running schedule after a restart.
        TaskConfig.Meta meta = task.getPublishedConfig() == null ? null : task.getPublishedConfig().getMeta();
        String cron = meta != null && meta.getCron() != null ? meta.getCron() : task.getCron();
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.warn("invalid cron for task {} : {}", task.getId(), e.getMessage());
            return false;
        }
        CronTrigger trigger = new CronTrigger(cron);
        ScheduledFuture<?> future = scheduler.schedule(() -> run(task.getId()), trigger);
        futures.put(task.getId(), future);
        return true;
    }

    private void cancelQuietly(String taskId) {
        ScheduledFuture<?> f = futures.remove(taskId);
        if (f != null) f.cancel(false);
    }

    /** Single execution: route by task type → call → assert → write call record → audit. */
    void run(String taskId) {
        SchedulerTask task = taskStore.findById(taskId).orElse(null);
        if (task == null || !task.isSchedulable()) {
            unschedule(taskId);
            return;
        }
        if (task.getTaskType() == io.litealert.scheduler.domain.SchedulerTaskType.TCP) {
            runTcp(taskId, task);
        } else {
            runApi(taskId, task);
        }
    }

    /** API execution path: HTTP call + response assertion (unchanged legacy behavior). */
    private void runApi(String taskId, SchedulerTask task) {
        ApiTaskConfig config = (ApiTaskConfig) task.getPublishedConfig();
        Instant triggeredAt = Instant.now();
        long start = System.currentTimeMillis();
        Integer httpStatus = null;
        boolean success = false;
        Boolean assertionPassed = null;
        String error = null;
        String excerpt = null;

        try {
            // outbound target guard (design D10): reject blocked hosts before the HTTP call
            java.net.URI uri = java.net.URI.create(config.getUrl());
            guard.check(uri.getHost(), uri.getPort() > 0 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80));
            ApiTaskHttpExecutor.Response res = httpExecutor.execute(config);
            httpStatus = res.status();
            excerpt = res.body();

            ApiTaskConfig.Assertion assertion = config.getAssertion();
            List<Condition> conditions = toConditions(assertion);
            if (!conditions.isEmpty() || assertion != null) {
                WebhookResponseAssertor.MultiResult ar = assertor.check(
                        conditions,
                        assertion == null ? Logic.AND : mapLogic(assertion.getLogic()),
                        res.status(), res.contentType(), res.body());
                assertionPassed = ar.success();
                success = ar.success();
                if (!success && ar.message() != null) error = ar.message();
            } else {
                // no assertion → judge by HTTP status (assertor already requires 2xx, but be explicit)
                success = res.status() >= 200 && res.status() < 300;
                if (!success) error = "HTTP " + res.status();
            }
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("scheduled task {} timed out: {}", taskId, e.getMessage());
            error = "请求超时：" + e.getMessage();
            success = false;
        } catch (java.net.ConnectException e) {
            log.warn("scheduled task {} connect failed: {}", taskId, e.getMessage());
            error = "连接失败/超时：" + sanitize(e.getMessage());
            success = false;
        } catch (io.litealert.common.error.BusinessException e) {
            // outbound target guard rejection (TARGET_BLOCKED) or other business error
            log.warn("scheduled task {} blocked/failed: {}", taskId, e.getMessage());
            error = sanitize(e.getMessage());
            success = false;
            if (e.getCode() == io.litealert.common.error.ErrorCode.TARGET_BLOCKED) {
                audit.log("scheduler.task.target-blocked", java.util.Map.of(
                        "taskId", taskId, "protocol", "API", "error", error));
            }
        } catch (Exception e) {
            log.warn("scheduled task {} failed", taskId, e);
            error = sanitize(e.getMessage());
            success = false;
        }

        long durationMs = System.currentTimeMillis() - start;
        SchedulerTaskCall call = SchedulerTaskCall.builder()
                .id(IdGenerator.entityId("stc"))
                .taskId(taskId)
                .triggeredAt(triggeredAt)
                .protocol("API")
                .method(config.getMethod())
                .url(config.getUrl())
                .httpStatus(httpStatus)
                .durationMs(durationMs)
                .status(success ? SchedulerTaskCall.Status.SUCCESS : SchedulerTaskCall.Status.FAIL)
                .assertionPassed(assertionPassed)
                .errorMessage(error)
                .responseExcerpt(excerpt)
                .createdAt(Instant.now())
                .build();
        callStore.insert(call);

        audit.log(success ? "scheduler.task.success" : "scheduler.task.failed",
                java.util.Map.of(
                        "taskId", taskId,
                        "httpStatus", httpStatus == null ? "" : httpStatus,
                        "durationMs", durationMs,
                        "success", success));
        if (!success && error != null && error.contains("超时")) {
            audit.log("scheduler.task.timeout", java.util.Map.of(
                    "taskId", taskId, "durationMs", durationMs, "error", error));
        }

        dispatchNotifications(task, call);
    }

    /** TCP execution path: connectivity probe (design D3/D4). No assertion; tcpOk gates success. */
    private void runTcp(String taskId, SchedulerTask task) {
        io.litealert.scheduler.domain.TcpTaskConfig config =
                (io.litealert.scheduler.domain.TcpTaskConfig) task.getPublishedConfig();
        Instant triggeredAt = Instant.now();
        long start = System.currentTimeMillis();
        boolean success = false;
        Boolean tcpOk = null;
        String error = null;
        String excerpt = null;
        String tcpTarget = config.getHost() + ":" + config.getPort();

        try {
            // outbound target guard (design D10): reject blocked hosts before the TCP connect
            guard.check(config.getHost(), config.getPort());
            ApiTaskTcpExecutor.Result res = tcpExecutor.execute(config);
            tcpOk = res.connected();
            success = res.connected();
            if (success) {
                excerpt = "connected to " + tcpTarget + " in " + (System.currentTimeMillis() - start) + "ms";
            }
        } catch (java.net.SocketTimeoutException e) {
            log.warn("scheduled task {} tcp timed out: {}", taskId, e.getMessage());
            tcpOk = false;
            error = "连接超时：" + sanitize(e.getMessage());
            success = false;
        } catch (java.net.ConnectException e) {
            log.warn("scheduled task {} tcp refused: {}", taskId, e.getMessage());
            tcpOk = false;
            error = "连接被拒绝：" + sanitize(e.getMessage());
            success = false;
        } catch (java.net.UnknownHostException e) {
            log.warn("scheduled task {} tcp unknown host: {}", taskId, e.getMessage());
            tcpOk = false;
            error = "主机名解析失败：" + sanitize(e.getMessage());
            success = false;
        } catch (io.litealert.common.error.BusinessException e) {
            // guard rejection or other business error → fail without tcpOk semantics
            log.warn("scheduled task {} tcp blocked/failed: {}", taskId, e.getMessage());
            error = sanitize(e.getMessage());
            success = false;
            if (e.getCode() == io.litealert.common.error.ErrorCode.TARGET_BLOCKED) {
                audit.log("scheduler.task.target-blocked", java.util.Map.of(
                        "taskId", taskId, "protocol", "TCP", "target", tcpTarget, "error", error));
            }
        } catch (Exception e) {
            log.warn("scheduled task {} tcp failed", taskId, e);
            tcpOk = false;
            error = sanitize(e.getMessage());
            success = false;
        }

        long durationMs = System.currentTimeMillis() - start;
        SchedulerTaskCall call = SchedulerTaskCall.builder()
                .id(IdGenerator.entityId("stc"))
                .taskId(taskId)
                .triggeredAt(triggeredAt)
                .protocol("TCP")
                .tcpTarget(tcpTarget)
                .tcpOk(tcpOk)
                .durationMs(durationMs)
                .status(success ? SchedulerTaskCall.Status.SUCCESS : SchedulerTaskCall.Status.FAIL)
                .errorMessage(error)
                .responseExcerpt(excerpt)
                .createdAt(Instant.now())
                .build();
        callStore.insert(call);

        audit.log(success ? "scheduler.task.success" : "scheduler.task.failed",
                java.util.Map.of(
                        "taskId", taskId,
                        "protocol", "TCP",
                        "tcpOk", tcpOk == null ? "" : tcpOk,
                        "durationMs", durationMs,
                        "success", success));
        if (!success && error != null && error.contains("超时")) {
            audit.log("scheduler.task.timeout", java.util.Map.of(
                    "taskId", taskId, "durationMs", durationMs, "error", error));
        }

        dispatchNotifications(task, call);
    }

    /** Fire bound notify configs whose triggerOn matches this run; each failure is audited only. */
    private void dispatchNotifications(SchedulerTask task, SchedulerTaskCall call) {
        List<String> ids = publishedNotifyIds(task);
        if (ids.isEmpty()) return;
        SchedulerNotifier.RenderContext ctx = new SchedulerNotifier.RenderContext(
                task.getId(), task.getName(),
                call.getProtocol() == null ? "API" : call.getProtocol(),
                call.getStatus() == SchedulerTaskCall.Status.SUCCESS,
                call.getHttpStatus(), call.getDurationMs() == null ? 0L : call.getDurationMs(),
                call.getErrorMessage(), call.getTriggeredAt(), call.getAssertionPassed(),
                call.getResponseExcerpt());
        for (io.litealert.scheduler.domain.SchedulerNotifyConfig cfg : notifyConfigStore.findByIds(ids)) {
            if (!notifier.shouldFire(cfg, ctx.success())) continue;
            try {
                boolean ok = notifier.send(cfg, ctx);
                if (!ok) {
                    audit.log("scheduler.notify.failed", java.util.Map.of(
                            "taskId", task.getId(), "notifyId", cfg.getId(), "url", String.valueOf(cfg.getUrl())));
                }
            } catch (Exception e) {
                log.warn("notify dispatch error taskId={} notifyId={}", task.getId(), cfg.getId(), e);
                audit.log("scheduler.notify.failed", java.util.Map.of(
                        "taskId", task.getId(), "notifyId", cfg.getId(), "error", sanitize(e.getMessage())));
            }
        }
    }

    /** Published-track notify ids live in publishedConfig.meta.notifyConfigIds. */
    private List<String> publishedNotifyIds(SchedulerTask task) {
        if (task.getPublishedConfig() == null || task.getPublishedConfig().getMeta() == null) return List.of();
        List<String> ids = task.getPublishedConfig().getMeta().getNotifyConfigIds();
        return ids == null ? List.of() : ids;
    }

    private List<Condition> toConditions(ApiTaskConfig.Assertion assertion) {
        if (assertion == null || assertion.getConditions() == null) return List.of();
        return assertion.getConditions().stream()
                .filter(c -> c.getPath() != null && !c.getPath().isBlank())
                .map(c -> new Condition(c.getPath(), mapOperator(c.getOperator()), c.getExpected(),
                        mapBodyType(c.getBodyType())))
                .toList();
    }

    private Operator mapOperator(ApiTaskConfig.Condition.Operator op) {
        if (op == null) return Operator.EQ;
        return Operator.valueOf(op.name());
    }

    private Logic mapLogic(ApiTaskConfig.Assertion.Logic logic) {
        if (logic == null) return Logic.AND;
        return Logic.valueOf(logic.name());
    }

    private WebhookResponseAssertor.ConditionBodyType mapBodyType(ApiTaskConfig.Condition.BodyType bt) {
        if (bt == null) return WebhookResponseAssertor.ConditionBodyType.AUTO;
        return WebhookResponseAssertor.ConditionBodyType.valueOf(bt.name());
    }

    private String sanitize(String message) {
        if (message == null) return "";
        String s = message.replaceAll("(?i)(password|token|secret|authorization|apikey|api_key)=\\S+", "$1=***");
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
