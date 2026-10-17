package run.endive.cm.wasi.p2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Descriptor;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.OpenFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.PathFlags;
import run.endive.cm.wasi.p2.bindings.wasi.io.poll.Pollable;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.InputStream;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.OutputStream;

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

    @Test
    void aFileStreamIsPolledBesideATimerOnAnEventLoop() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(Configuration.unix());
                var wasi =
                        WasiPreview2.builder()
                                .withScheduler(eventLoop)
                                .withDirectory("data", Files.createDirectory(fs.getPath("/data")))
                                .build()) {
            Descriptor file =
                    wasi.preopens()
                            .getDirectories()
                            .get(0)
                            .f0()
                            .openAt(
                                    PathFlags.of(),
                                    "file.txt",
                                    OpenFlags.of(OpenFlags.Flag.CREATE),
                                    DescriptorFlags.of(
                                            DescriptorFlags.Flag.READ, DescriptorFlags.Flag.WRITE));
            OutputStream out = file.writeViaStream(BigInteger.ZERO);
            out.blockingWriteAndFlush("hello".getBytes(UTF_8));
            InputStream in = file.readViaStream(BigInteger.ZERO);
            Pollable minuteLater =
                    wasi.monotonicClock().subscribeDuration(BigInteger.valueOf(60_000_000_000L));

            assertEquals(List.of(1L), wasi.poll().poll(List.of(minuteLater, in.subscribe())));
            assertArrayEquals("hello".getBytes(UTF_8), in.blockingRead(BigInteger.TEN));
            assertArrayEquals(
                    "hello".getBytes(UTF_8), Files.readAllBytes(fs.getPath("/data/file.txt")));
        }
    }
}
