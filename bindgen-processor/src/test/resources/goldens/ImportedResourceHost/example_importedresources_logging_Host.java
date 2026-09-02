package endive.testing.example.importedresources.logging;

import javax.annotation.processing.Generated;

/**
 * The WIT interface {@code example:imported-resources/logging}, which the embedder implements.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public interface Host {

    /**
     * Makes a {@code logger}.
     */
    Logger logger(Level maxLevel);
}
