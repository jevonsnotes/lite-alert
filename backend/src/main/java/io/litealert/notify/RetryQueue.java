package io.litealert.notify;

import io.litealert.common.audit.AuditLogger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory exponential-backoff retry queue. Each task carries enough state
 * to re-send (subject + body already rendered).
 *
 * <p>Caps at 5 attempts: 1m → 5m → 30m → 2h → 6h, then audit + drop.
 */
@Slf4j
@Component
public class RetryQueue {

    private static final long[] BACKOFF_SECONDS = {60, 300, 1_800, 7_200, 21_600};

    private final AuditLogger audit;
    /** Provider avoids the cycle: dispatcher → retryQueue → dispatcher. */
    private final ObjectProvider<NotifyDispatcher> dispatcherProvider;

    public RetryQueue(AuditLogger audit, ObjectProvider<NotifyDispatcher> dispatcherProvider) {
        this.audit = audit;
        this.dispatcherProvider = dispatcherProvider;
    }

    private final DelayQueue<DelayedTask> queue = new DelayQueue<>();
    private ScheduledExecutorService worker;

    @PostConstruct
    void start() {
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lite-alert-retry");
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::loop);
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.shutdownNow();
    }

    public void enqueue(RetryTask task) {
        if (task.attempt() > BACKOFF_SECONDS.length) {
            audit.log("notify.give_up", Map.of(
                    "topicId", task.ctx().topic().getId(),
                    "targetId", task.targetId(),
                    "attempt", task.attempt()));
            return;
        }
        long delaySeconds = BACKOFF_SECONDS[task.attempt() - 1];
        Instant fireAt = Instant.now().plusSeconds(delaySeconds);
        queue.put(new DelayedTask(task, fireAt));
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DelayedTask dt = queue.take();
                RetryTask t = dt.task;
                NotifyDispatcher d = dispatcherProvider.getObject();
                d.retrySend(t.ctx(), t.targetId(), t.attempt());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("retry loop iteration failed", e);
            }
        }
    }

    /** Re-rendering on retry is cheap; no need to carry rendered text around. */
    public record RetryTask(
            NotifyDispatcher.NotifyContext ctx,
            String targetId,
            int attempt
    ) {}

    private record DelayedTask(RetryTask task, Instant fireAt) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            long ms = fireAt.toEpochMilli() - System.currentTimeMillis();
            return unit.convert(ms, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
