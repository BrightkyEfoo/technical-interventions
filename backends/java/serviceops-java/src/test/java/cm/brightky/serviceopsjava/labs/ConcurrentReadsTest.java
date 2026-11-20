package cm.brightky.serviceopsjava.labs;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentReadsTest {
    @Test
    void returnsResultsInInputOrder() throws Exception {
        assertEquals(List.of("requests", "notifications"),
                ConcurrentReads.run(() -> "requests", () -> "notifications", Duration.ofSeconds(1)));
    }

    @Test
    void interruptsTheSlowDependencyOnFailure() {
        var started = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var blocker = new CountDownLatch(1);
        var failure = assertThrows(ExecutionException.class, () -> ConcurrentReads.run(() -> {
            started.countDown();
            try { blocker.await(); }
            catch (InterruptedException cancelled) { interrupted.set(true); throw cancelled; }
            return "unreachable";
        }, () -> {
            if (!started.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Slow task not started");
            throw new IllegalStateException("Remote failure");
        }, Duration.ofSeconds(3)));
        assertEquals("Remote failure", failure.getCause().getMessage());
        assertTrue(interrupted.get());
    }

    @Test
    void enforcesCommonDeadline() {
        var blocker = new CountDownLatch(1);
        assertThrows(TimeoutException.class, () -> ConcurrentReads.run(() -> {
            blocker.await();
            return "unreachable";
        }, () -> "ready", Duration.ofMillis(50)));
    }
}
