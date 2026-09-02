package endive.testing.exports.example.exportedresources.logging;

import javax.annotation.processing.Generated;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.runtime.GuestResource;

/**
 * The WIT resource {@code logger}, which {@code example:exported-resources/logging} implements. Nothing destroys it on the embedder's behalf, so closing one is what runs the guest's destructor.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class Logger implements AutoCloseable {

    private final Guest owner;

    private final ResourceValue handle;

    private boolean dropped;

    Logger(Guest owner, ResourceValue handle) {
        this.owner = owner;
        this.handle = handle;
    }

    public Level getMaxLevel() {
        return Level.fromComponent(owner.loggerGetMaxLevel.apply(handle)[0]);
    }

    public void setMaxLevel(Level level) {
        owner.loggerSetMaxLevel.apply(handle, level.toComponent());
    }

    public void log(Level level, String msg) {
        owner.loggerLog.apply(handle, level.toComponent(), msg);
    }

    /**
     * Runs the guest's destructor. Doing so more than once does nothing.
     */
    @Override
    public void close() {
        if (!dropped) {
            dropped = true;
            GuestResource.drop(handle);
        }
    }
}
