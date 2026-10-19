package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An open file shared by a descriptor and the streams opened from it, closed once every one of
 * them has let go. A stream outlives the descriptor it was opened from.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
final class SharedChannel {

    private final FileChannel channel;
    private final OpenFiles files;
    private final AtomicInteger holders = new AtomicInteger(1);

    /** A channel held by its first holder, which {@code files} closes if nothing else does. */
    SharedChannel(FileChannel channel, OpenFiles files) {
        this.channel = channel;
        this.files = files;
        files.add(this);
    }

    FileChannel channel() {
        return channel;
    }

    /** Adds a holder, which has to {@link #release} it once. */
    SharedChannel retain() {
        holders.incrementAndGet();
        return this;
    }

    /** Lets go of the channel, closing it when no holder is left. */
    void release() {
        if (holders.decrementAndGet() == 0) {
            files.remove(this);
            try {
                channel.close();
            } catch (IOException e) {
                // Nothing is left that could observe the failure.
            }
        }
    }
}
