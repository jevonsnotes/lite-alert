package io.litealert.common.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Filesystem-backed document store with three guarantees:
 *
 * <ol>
 *   <li><b>Atomic write</b> — write to {@code .tmp} then {@code Files.move}
 *       with {@code ATOMIC_MOVE}; keeps a one-deep {@code .bak}.</li>
 *   <li><b>Per-path RW lock</b> — multiple readers, single writer.</li>
 *   <li><b>Encrypted-at-rest</b> via the storeObjectMapper which honors
 *       {@link io.litealert.common.crypto.Encrypted}.</li>
 * </ol>
 *
 * <p>This is the lowest-level layer; domain-specific stores compose it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileStore {

    private final LiteAlertProperties props;

    @Qualifier("storeObjectMapper")
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    private Path root;

    @PostConstruct
    void init() {
        this.root = Path.of(props.getDataDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir " + root, e);
        }
        log.info("FileStore root = {}", root);
    }

    public Path root() { return root; }

    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("path escapes data root: " + relative);
        }
        return p;
    }

    public boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /** Run a block while holding the read lock for the given path. */
    public <T> T withReadLock(String relative, Supplier<T> body) {
        var lock = lockFor(relative).readLock();
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** Run a block while holding the write lock for the given path. */
    public <T> T withWriteLock(String relative, Supplier<T> body) {
        var lock = lockFor(relative).writeLock();
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** Read JSON from {@code relative} or return {@code defaultValue} if missing. */
    public <T> T readJson(String relative, TypeReference<T> type, T defaultValue) {
        return withReadLock(relative, () -> {
            Path p = resolve(relative);
            if (!Files.exists(p)) return defaultValue;
            try {
                return mapper.readValue(p.toFile(), type);
            } catch (IOException e) {
                log.error("read failed: {}", p, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "data file unreadable: " + relative);
            }
        });
    }

    /** Atomic write JSON to {@code relative}. */
    public void writeJson(String relative, Object value) {
        withWriteLock(relative, () -> {
            Path target = resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, mapper.writeValueAsBytes(value));
                if (Files.exists(target)) {
                    Path bak = target.resolveSibling(target.getFileName() + ".bak");
                    try {
                        Files.move(target, bak,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        // best-effort backup; fall through to atomic replace
                        log.warn("backup of {} failed, continuing", target, e);
                    }
                }
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.error("write failed: {}", target, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "data file unwritable: " + relative);
            }
            return null;
        });
    }

    public void delete(String relative) {
        withWriteLock(relative, () -> {
            try {
                Files.deleteIfExists(resolve(relative));
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "data file undeletable: " + relative);
            }
            return null;
        });
    }

    private ReentrantReadWriteLock lockFor(String relative) {
        return locks.computeIfAbsent(relative, k -> new ReentrantReadWriteLock());
    }
}
