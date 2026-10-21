package run.endive.cm.wasi.p2.sockets.netty;

import io.netty.channel.ChannelFactory;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.uring.IoUringDatagramChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;
import java.util.Objects;

/**
 * The Netty channels from which the network creates its sockets. They have to suit the event loop
 * group that runs the network.
 */
final class NettyChannels {

    private final ChannelFactory<? extends SocketChannel> socket;
    private final ChannelFactory<? extends ServerSocketChannel> serverSocket;
    private final ChannelFactory<? extends DatagramChannel> datagram;

    private NettyChannels(
            ChannelFactory<? extends SocketChannel> socket,
            ChannelFactory<? extends ServerSocketChannel> serverSocket,
            ChannelFactory<? extends DatagramChannel> datagram) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.serverSocket = Objects.requireNonNull(serverSocket, "serverSocket");
        this.datagram = Objects.requireNonNull(datagram, "datagram");
    }

    /**
     * @param socket creates the channel of a TCP connection
     * @param serverSocket creates the channel of a listening TCP socket
     * @param datagram creates the channel of a UDP socket
     */
    static NettyChannels of(
            ChannelFactory<? extends SocketChannel> socket,
            ChannelFactory<? extends ServerSocketChannel> serverSocket,
            ChannelFactory<? extends DatagramChannel> datagram) {
        return new NettyChannels(socket, serverSocket, datagram);
    }

    ChannelFactory<? extends SocketChannel> socket() {
        return socket;
    }

    ChannelFactory<? extends ServerSocketChannel> serverSocket() {
        return serverSocket;
    }

    ChannelFactory<? extends DatagramChannel> datagram() {
        return datagram;
    }

    /**
     * The channels of the transport {@code group} runs, among those Netty ships. A native
     * transport is considered only once its classes are on the class path.
     *
     * @throws IllegalArgumentException when {@code group} runs another transport
     */
    static NettyChannels forGroup(IoEventLoopGroup group) {
        if (group.isIoType(NioIoHandler.class)) {
            return Nio.CHANNELS;
        }
        if (isPresent("io.netty.channel.epoll.EpollIoHandler") && Epoll.runs(group)) {
            return Epoll.CHANNELS;
        }
        if (isPresent("io.netty.channel.uring.IoUringIoHandler") && IoUring.runs(group)) {
            return IoUring.CHANNELS;
        }
        if (isPresent("io.netty.channel.kqueue.KQueueIoHandler") && KQueue.runs(group)) {
            return KQueue.CHANNELS;
        }
        throw new IllegalArgumentException(
                "The network runs on the scheduler, a Netty event loop group whose transport is"
                    + " none of NIO, epoll, io_uring, or kqueue. Use one of those, or a scheduler"
                    + " outside Netty to give the network a NIO group of its own. Got "
                        + group.getClass().getName()
                        + ".");
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, NettyChannels.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static final class Nio {

        static final NettyChannels CHANNELS =
                of(NioSocketChannel::new, NioServerSocketChannel::new, NioDatagramChannel::new);

        private Nio() {}
    }

    private static final class Epoll {

        static final NettyChannels CHANNELS =
                of(
                        EpollSocketChannel::new,
                        EpollServerSocketChannel::new,
                        EpollDatagramChannel::new);

        private Epoll() {}

        static boolean runs(IoEventLoopGroup group) {
            return group.isIoType(EpollIoHandler.class);
        }
    }

    private static final class IoUring {

        static final NettyChannels CHANNELS =
                of(
                        IoUringSocketChannel::new,
                        IoUringServerSocketChannel::new,
                        IoUringDatagramChannel::new);

        private IoUring() {}

        static boolean runs(IoEventLoopGroup group) {
            return group.isIoType(IoUringIoHandler.class);
        }
    }

    private static final class KQueue {

        static final NettyChannels CHANNELS =
                of(
                        KQueueSocketChannel::new,
                        KQueueServerSocketChannel::new,
                        KQueueDatagramChannel::new);

        private KQueue() {}

        static boolean runs(IoEventLoopGroup group) {
            return group.isIoType(KQueueIoHandler.class);
        }
    }
}
