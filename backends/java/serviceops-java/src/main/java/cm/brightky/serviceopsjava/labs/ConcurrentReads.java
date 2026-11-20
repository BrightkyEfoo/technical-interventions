package cm.brightky.serviceopsjava.labs;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Lab: two independent, interruptible dependencies with a shared deadline. */
public final class ConcurrentReads {
    public static <T> List<T> run(Callable<T> first, Callable<T> second, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var completion = new ExecutorCompletionService<T>(pool);
            var left = completion.submit(first);
            var right = completion.submit(second);
            var pending = Set.of(left, right);
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                for (int read = 0; read < pending.size(); read++) {
                    var completed = completion.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (completed == null) throw new TimeoutException("Read deadline exceeded");
                    completed.get();
                }
                return List.of(left.get(), right.get());
            } finally {
                pending.forEach(task -> task.cancel(true));
            }
        }
    }
}
