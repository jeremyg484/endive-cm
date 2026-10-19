package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The files guests hold open through one host's directories, so closing the host closes them too.
 * A guest using one afterwards fails with {@code bad-descriptor}.
 */
public final class OpenFiles implements AutoCloseable {

    private final Set<SharedChannel> open = ConcurrentHashMap.newKeySet();

    void add(SharedChannel channel) {
        open.add(channel);
    }

    void remove(SharedChannel channel) {
        open.remove(channel);
    }

    /** Closes every file a guest left open. */
    @Override
    public void close() {
        for (SharedChannel channel : open) {
            try {
                channel.channel().close();
            } catch (IOException e) {
                // Nothing is left that could observe the failure.
            }
        }
        open.clear();
    }
}
