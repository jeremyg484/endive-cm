package run.endive.cm.wasi.p2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
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
import run.endive.cm.wasi.p2.random.insecure.HostInsecure;
import run.endive.cm.wasi.p2.random.insecureseed.HostInsecureSeed;
import run.endive.cm.wasi.p2.random.random.HostRandom;
import run.endive.cm.wasi.p2.sockets.HostSockets;
import run.endive.cm.wasi.p2.sockets.HostSocketsFactory;
import run.endive.cm.wasi.p2.sockets.NameResolver;
import run.endive.cm.wasi.p2.sockets.NetworkPolicy;
import run.endive.cm.wasi.p2.sockets.instancenetwork.HostInstanceNetwork;
import run.endive.cm.wasi.p2.sockets.netty.NettyNameResolver;

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
    private final HostSockets sockets;
    private final HostMonotonicClock monotonicClock;
    private final HostWallClock wallClock;
    private final HostTypes types;
    private final OpenFiles files = new OpenFiles();
    private final HostPreopens preopens;
    private final HostRandom random;
    private final HostInsecure insecure;
    private final HostInsecureSeed insecureSeed;
    private final HostInstanceNetwork instanceNetwork;

    private WasiPreview2(Builder builder) {
        this.poll = new HostPoll();
        this.sockets =
                HostSocketsFactory.create(
                        builder.networkPolicy, poll, builder.scheduler, builder.nameResolver);
        this.ownsScheduler = builder.scheduler == null && sockets.eventLoopGroup() == null;
        if (builder.scheduler != null) {
            this.scheduler = builder.scheduler;
        } else if (sockets.eventLoopGroup() != null) {
            this.scheduler = sockets.eventLoopGroup();
        } else {
            this.scheduler = defaultScheduler();
        }
        this.monotonicClock = new HostMonotonicClock(scheduler, poll);
        this.wallClock =
                builder.clock == null
                        ? new HostWallClock()
                        : new HostWallClock(builder.clock, builder.clockResolution);
        this.types = new HostTypes();
        this.preopens = new HostPreopens(builder.directories, builder.readOnly, poll, files);
        this.random = builder.random == null ? new HostRandom() : new HostRandom(builder.random);
        this.insecure =
                builder.insecureRandom == null
                        ? new HostInsecure()
                        : new HostInsecure(builder.insecureRandom);
        this.insecureSeed =
                builder.insecureSeed == null ? new HostInsecureSeed() : builder.insecureSeed;
        this.instanceNetwork = new HostInstanceNetwork(builder.networkPolicy);
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

    @Override
    public HostInstanceNetwork instanceNetwork() {
        return instanceNetwork;
    }

    @Override
    public run.endive.cm.wasi.p2.bindings.wasi.sockets.ipnamelookup.Host ipNameLookup() {
        return sockets.ipNameLookup();
    }

    @Override
    public run.endive.cm.wasi.p2.bindings.wasi.sockets.tcpcreatesocket.Host tcpCreateSocket() {
        return sockets.tcpCreateSocket();
    }

    @Override
    public run.endive.cm.wasi.p2.bindings.wasi.sockets.udpcreatesocket.Host udpCreateSocket() {
        return sockets.udpCreateSocket();
    }

    /**
     * Closes every file a guest left open and what the network created, then shuts down the
     * scheduler. Anything the builder was given stays open.
     */
    @Override
    public void close() {
        files.close();
        sockets.close();
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
        private final Set<String> readOnly = new HashSet<>();
        private Random random;
        private Random insecureRandom;
        private HostInsecureSeed insecureSeed;
        private NetworkPolicy networkPolicy = NetworkPolicy.denyAll();
        private NameResolver nameResolver;

        private Builder() {}

        /**
         * The executor that runs timers, such as those behind {@code wasi:clocks} subscriptions.
         * Any {@link ScheduledExecutorService} works, including an event loop the embedder already
         * runs. When it is a Netty event loop group, the network runs on it as well. Otherwise a
         * guest granted the network gets a one-thread NIO group of its own. A guest must never run
         * on its threads, since a blocked guest would hold up the timers it waits for. By default,
         * one is created and then shut down on {@link #close()}. When set by the embedder, it is
         * their responsibility to shut it down.
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
         *
         * @throws IllegalArgumentException when {@code host} is not a directory, or {@code guest}
         *     already names one
         */
        public Builder withDirectory(String guest, Path host) {
            return grant(guest, host, false);
        }

        /**
         * Grants the guest {@code host} as a preopened directory named {@code guest}, which it
         * may read but not change in any way.
         *
         * @throws IllegalArgumentException when {@code host} is not a directory, or {@code guest}
         *     already names one
         */
        public Builder withReadOnlyDirectory(String guest, Path host) {
            return grant(guest, host, true);
        }

        private Builder grant(String guest, Path host, boolean readOnly) {
            Objects.requireNonNull(guest, "guest");
            Objects.requireNonNull(host, "host");
            if (!Files.isDirectory(host)) {
                throw new IllegalArgumentException(host + " is not a directory");
            }
            if (directories.containsKey(guest)) {
                throw new IllegalArgumentException("a directory is already granted as " + guest);
            }
            directories.put(guest, host);
            if (readOnly) {
                this.readOnly.add(guest);
            }
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

        /**
         * What the guest may do on the network through {@code wasi:sockets}. By default, it may do
         * nothing. Granting it anything needs {@code io.netty:netty-transport} and {@code
         * io.netty:netty-resolver-dns} from Netty 4.2 on the class path.
         */
        public Builder withNetworkPolicy(NetworkPolicy policy) {
            this.networkPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * What resolves the names the guest looks up, which the embedder closes. {@link
         * NettyNameResolver#of} adapts a resolver from Netty.
         *
         * <p>By default, a Netty {@code DnsNameResolver} reads the host's resolver configuration
         * and hosts file, and runs on the network's event loop group. On macOS, it also needs
         * {@code io.netty:netty-resolver-dns-native-macos} to read the system's DNS servers, and
         * warns once without it. With no configuration to read, it asks Google's public DNS
         * servers.
         */
        public Builder withNameResolver(NameResolver resolver) {
            this.nameResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public WasiPreview2 build() {
            return new WasiPreview2(this);
        }
    }
}
