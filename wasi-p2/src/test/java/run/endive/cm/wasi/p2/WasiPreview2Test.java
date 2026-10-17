package run.endive.cm.wasi.p2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.endive.cm.runtime.Tuple2;
import run.endive.cm.wasi.p2.bindings.wasi.clocks.wallclock.Datetime;

class WasiPreview2Test {

    /** A clock subscription is a pollable the same host can poll. */
    @Test
    void aClockSubscriptionCanBePolled() {
        try (var wasi = WasiPreview2.builder().build()) {
            var timeout = wasi.monotonicClock().subscribeDuration(BigInteger.valueOf(1_000_000));

            assertEquals(List.of(0L), wasi.poll().poll(List.of(timeout)));
        }
    }

    @Test
    void closingShutsDownTheSchedulerItCreated() {
        var wasi = WasiPreview2.builder().build();
        wasi.close();

        assertThrows(
                RejectedExecutionException.class,
                () -> wasi.monotonicClock().subscribeDuration(BigInteger.valueOf(1_000_000)));
    }

    @Test
    void closingLeavesAGivenSchedulerRunning() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            WasiPreview2.builder().withScheduler(scheduler).build().close();

            assertFalse(scheduler.isShutdown());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aGivenWallClockIsRead() {
        Clock fixed = Clock.fixed(Instant.ofEpochSecond(42), ZoneOffset.UTC);

        try (var wasi = WasiPreview2.builder().withWallClock(fixed, Duration.ofNanos(1)).build()) {
            assertEquals(new Datetime(BigInteger.valueOf(42), 0L), wasi.wallClock().now());
        }
    }

    @Test
    void aWallClockNeedsAResolution() {
        Clock fixed = Clock.fixed(Instant.ofEpochSecond(42), ZoneOffset.UTC);

        assertThrows(
                NullPointerException.class,
                () -> WasiPreview2.builder().withWallClock(fixed, null));
    }

    @Test
    void eachGivenDirectoryIsPreopened() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(Configuration.unix());
                var wasi =
                        WasiPreview2.builder()
                                .withDirectory("/data", Files.createDirectory(fs.getPath("/data")))
                                .withDirectory(".", Files.createDirectory(fs.getPath("/scratch")))
                                .build()) {
            assertEquals(
                    List.of("/data", "."),
                    wasi.preopens().getDirectories().stream()
                            .map(Tuple2::f1)
                            .collect(Collectors.toList()));
        }
    }

    @Test
    void noDirectoryIsPreopenedByDefault() {
        try (var wasi = WasiPreview2.builder().build()) {
            assertEquals(List.of(), wasi.preopens().getDirectories());
        }
    }
}
