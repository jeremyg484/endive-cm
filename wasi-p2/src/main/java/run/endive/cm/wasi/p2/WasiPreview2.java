package run.endive.cm.wasi.p2;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;
import run.endive.cm.wasi.p2.bindings.ImportsWorld;
import run.endive.cm.wasi.p2.clocks.monotonicclock.HostMonotonicClock;
import run.endive.cm.wasi.p2.clocks.wallclock.HostWallClock;
import run.endive.cm.wasi.p2.filesystem.preopens.HostPreopens;
import run.endive.cm.wasi.p2.filesystem.types.HostTypes;
import run.endive.cm.wasi.p2.filesystem.types.OpenFiles;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

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
    private final HostTypes types;
    private final OpenFiles files = new OpenFiles();
    private final HostPreopens preopens;

    private WasiPreview2(Builder builder) {
        this.ownsScheduler = builder.scheduler == null;
        this.scheduler = ownsScheduler ? defaultScheduler() : builder.scheduler;
        this.poll = new HostPoll();
        this.monotonicClock = new HostMonotonicClock(scheduler, poll);
        this.wallClock =
                builder.clock == null
                        ? new HostWallClock()
                        : new HostWallClock(builder.clock, builder.clockResolution);
        this.types = new HostTypes();
        this.preopens = new HostPreopens(builder.directories, poll, files);
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
    public HostTypes types() {
        return types;
    }

    @Override
    public HostPreopens preopens() {
        return preopens;
    }

    /**
     * Closes every file a guest left open, and shuts down the scheduler unless the builder was
     * given an external one to use.
     */
    @Override
    public void close() {
        files.close();
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
        private final Map<String, Path> directories = new LinkedHashMap<>();

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
         * Grants the guest {@code host} as a preopened directory named {@code guest}. Any
         * {@link java.nio.file.FileSystem} works, so an in-memory one makes a sandbox that touches
         * no host files.
         *
         * <p>The guest cannot reach outside {@code host} by itself. Java checks a path and then
         * acts on it in separate steps, though, so nothing else may change {@code host} while an
         * untrusted guest uses it.
         */
        public Builder withDirectory(String guest, Path host) {
            directories.put(
                    Objects.requireNonNull(guest, "guest"), Objects.requireNonNull(host, "host"));
            return this;
        }

        public WasiPreview2 build() {
            return new WasiPreview2(this);
        }
    }
}
