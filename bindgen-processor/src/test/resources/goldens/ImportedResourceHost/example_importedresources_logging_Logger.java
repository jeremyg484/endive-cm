package endive.testing.example.importedresources.logging;

import javax.annotation.processing.Generated;

/**
 * The WIT resource {@code logger}, which the embedder implements. A method's borrowed receiver is what Java carries as {@code this}, so it is not a parameter here.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public interface Logger {

    Level getMaxLevel();

    void setMaxLevel(Level level);

    void log(Level level, String msg);

    /**
     * Called when the guest drops an owned handle to this resource.
     */
    default void drop() {
    }
}
