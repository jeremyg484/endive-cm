package run.endive.cm.wasi.p2;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;
import run.endive.cm.wasi.p2.bindings.ImportsWorld;
import run.endive.cm.wasi.p2.clocks.monotonicclock.HostMonotonicClock;
import run.endive.cm.wasi.p2.clocks.wallclock.HostWallClock;
import run.endive.cm.wasi.p2.io.poll.HostPoll;
import run.endive.cm.wasi.p2.random.insecure.HostInsecure;
import run.endive.cm.wasi.p2.random.insecureseed.HostInsecureSeed;
import run.endive.cm.wasi.p2.random.random.HostRandom;

/**
 * The WASI preview 2 interfaces this host implements, ready to satisfy a component's imports.
 *
 * <p>Blocking calls such as {@code poll} wait on the thread the guest runs on, which may be a
 * virtual thread.
 *
 * <pre>{@code
 * try (var wasi = WasiPreview2.builder().build()) {
 *     ImportsWorld world = wasi.instantiate(new ComponentStore(), component);
 * }
 * }</pre>
 */
public final class WasiPreview2 implements ImportsWorld.Imports, AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final HostPoll poll;
    private final HostMonotonicClock monotonicClock;
    private final HostWallClock wallClock;
    private final HostRandom random;
    private final HostInsecure insecure;
    private final HostInsecureSeed insecureSeed;

    private WasiPreview2(Builder builder) {
        this.ownsScheduler = builder.scheduler == null;
        this.scheduler = ownsScheduler ? defaultScheduler() : builder.scheduler;
        this.poll = new HostPoll();
        this.monotonicClock = new HostMonotonicClock(scheduler, poll);
        this.wallClock =
                builder.clock == null
                        ? new HostWallClock()
                        : new HostWallClock(builder.clock, builder.clockResolution);
        this.random = builder.random == null ? new HostRandom() : new HostRandom(builder.random);
        this.insecure =
                builder.insecureRandom == null
                        ? new HostInsecure()
                        : new HostInsecure(builder.insecureRandom);
        this.insecureSeed =
                builder.insecureSeed == null ? new HostInsecureSeed() : builder.insecureSeed;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Instantiates {@code component}, satisfying its WASI imports with this host. */
    public ImportsWorld instantiate(ComponentStore store, WasmComponent component) {
        return ImportsWorld.instantiate(store, component, this);
    }

    @Override
    public HostPoll poll() {
        return poll;
    }

    @Override
    public HostMonotonicClock monotonicClock() {
        return monotonicClock;
    }

    @Override
    public HostWallClock wallClock() {
        return wallClock;
    }

    @Override
    public HostRandom random() {
        return random;
    }

    @Override
    public HostInsecure insecure() {
        return insecure;
    }

    @Override
    public HostInsecureSeed insecureSeed() {
        return insecureSeed;
    }

    /** Shuts down the scheduler, unless the builder was given an external one to use. */
    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            Thread thread = new Thread(task, "endive-wasi-p2-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    public static final class Builder {

        private ScheduledExecutorService scheduler;
        private Clock clock;
        private Duration clockResolution;
        private Random random;
        private Random insecureRandom;
        private HostInsecureSeed insecureSeed;

        private Builder() {}

        /**
         * The executor that runs timers, such as those behind {@code wasi:clocks} subscriptions.
         * Any {@link ScheduledExecutorService} works, including an event loop the embedder already
         * runs. A guest must never run on its threads, since a blocked guest would hold up the
         * timers it waits for. By default, one is created and then shut down on {@link #close()}.
         * When set by the embedder, it is their responsibility to shut it down.
         */
        public Builder withScheduler(ScheduledExecutorService scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            return this;
        }

        /**
         * The clock {@code wasi:clocks/wall-clock} reads, which defaults to the system clock.
         *
         * @param resolution the time one tick of {@code clock} stands for
         */
        public Builder withWallClock(Clock clock, Duration resolution) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.clockResolution = Objects.requireNonNull(resolution, "resolution");
            return this;
        }

        /**
         * The generator {@code wasi:random/random} draws from, which defaults to the {@link
         * java.util.concurrent.ThreadLocalRandom} of the thread running the guest, as in Endive's
         * WASI preview 1. Pass a {@link java.security.SecureRandom} for cryptographic strength. A
         * seeded generator makes every value predictable, so it only suits tests.
         */
        public Builder withRandom(Random random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * The generator {@code wasi:random/insecure} draws from, which defaults to the {@link
         * java.util.concurrent.ThreadLocalRandom} of the thread running the guest.
         */
        public Builder withInsecureRandom(Random random) {
            this.insecureRandom = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * The seed {@code wasi:random/insecure-seed} returns, whose halves hold the bits of {@code
         * first} and {@code second}. By default, one is drawn when the host is built.
         */
        public Builder withInsecureSeed(long first, long second) {
            this.insecureSeed = new HostInsecureSeed(first, second);
            return this;
        }

        public WasiPreview2 build() {
            return new WasiPreview2(this);
        }
    }
}
