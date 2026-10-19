package run.endive.cm.wasi.p2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.endive.cm.wasi.p2.bindings.wasi.io.poll.Pollable;

/** An embedder already running Netty plugs its event loop and futures in without adapters of ours. */
class NettySwapInTest {

    private EventExecutor eventLoop;

    @BeforeEach
    void startEventLoop() {
        eventLoop = new DefaultEventExecutor();
    }

    @AfterEach
    void stopEventLoop() {
        eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void anEventLoopRunsTheTimers() {
        try (var wasi = WasiPreview2.builder().withScheduler(eventLoop).build()) {
            Pollable timeout =
                    wasi.monotonicClock().subscribeDuration(BigInteger.valueOf(1_000_000));

            assertEquals(List.of(0L), wasi.poll().poll(List.of(timeout)));
        }
    }

    @Test
    void aNettyFutureBacksAPollable() {
        try (var wasi = WasiPreview2.builder().withScheduler(eventLoop).build()) {
            Promise<Void> promise = eventLoop.newPromise();
            CompletableFuture<Void> adapted = new CompletableFuture<>();
            promise.addListener(done -> adapted.complete(null));
            eventLoop.schedule(() -> promise.setSuccess(null), 20, TimeUnit.MILLISECONDS);

            assertEquals(List.of(0L), wasi.poll().poll(List.of(wasi.poll().pollable(adapted))));
            assertTrue(promise.isDone());
        }
    }
}
