package dev.blockmate.server.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 每天固定时刻跑一次的小调度：日记、备份。 */
public final class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private MaintenanceScheduler() {
    }

    public static void schedule(ScheduledExecutorService exec, LocalTime at, Runnable job, String name) {
        long delay = millisUntilNext(at, ZonedDateTime.now());
        exec.schedule(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.warn("daily job {} failed: {}", name, e.toString());
            }
            schedule(exec, at, job, name);
        }, delay, TimeUnit.MILLISECONDS);
        log.debug("daily job {} in {} min", name, delay / 60000);
    }

    static long millisUntilNext(LocalTime at, ZonedDateTime now) {
        ZonedDateTime next = now.with(at).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }
}
