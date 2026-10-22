package run.endive.cm.wasi.p2.sockets.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import run.endive.cm.wasi.p2.WasiPreview2;
import run.endive.cm.wasi.p2.bindings.wasi.io.poll.Pollable;
import run.endive.cm.wasi.p2.bindings.wasi.sockets.ipnamelookup.ResolveAddressStream;
import run.endive.cm.wasi.p2.sockets.NetworkPolicy;

/**
 * The network runs on a scheduler using one of Netty's native transports, with the channels that
 * transport needs. Each test runs only where its transport is available.
 */
class NativeTransportTest {

    @Test
    void theNetworkRunsOnEpoll() {
        assumeTrue(Epoll.isAvailable(), "epoll is not available here");

        resolvesLocalhostOn(EpollIoHandler.newFactory());
    }

    @Test
    void theNetworkRunsOnIoUring() {
        assumeTrue(IoUring.isAvailable(), "io_uring is not available here");

        resolvesLocalhostOn(IoUringIoHandler.newFactory());
    }

    @Test
    void theNetworkRunsOnKQueue() {
        assumeTrue(KQueue.isAvailable(), "kqueue is not available here");

        resolvesLocalhostOn(KQueueIoHandler.newFactory());
    }

    /** Netty's resolver answers localhost from the hosts file, on the transport's channels. */
    private static void resolvesLocalhostOn(IoHandlerFactory transport) {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, transport);
        try (var wasi =
                WasiPreview2.builder()
                        .withScheduler(group)
                        .withNetworkPolicy(NetworkPolicy.builder().allowIpNameLookup().build())
                        .build()) {
            Pollable timeout =
                    wasi.monotonicClock().subscribeDuration(BigInteger.valueOf(1_000_000));
            ResolveAddressStream stream =
                    wasi.ipNameLookup()
                            .resolveAddresses(
                                    wasi.instanceNetwork().instanceNetwork(), "localhost");

            assertEquals(List.of(0L), wasi.poll().poll(List.of(timeout)));
            wasi.poll().poll(List.of(stream.subscribe()));
            assertNotNull(stream.resolveNextAddress());
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
