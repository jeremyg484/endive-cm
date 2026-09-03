package endive.testing.example.interfaceimports.logging;

import javax.annotation.processing.Generated;

/**
 * The WIT interface {@code example:interface-imports/logging}, which the embedder implements.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public interface Host {

    void log(Level level, String msg);
}
