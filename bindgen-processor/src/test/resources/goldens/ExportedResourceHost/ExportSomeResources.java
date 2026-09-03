package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:exported-resources/export-some-resources}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class ExportSomeResources {

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {
    }

    private final ComponentInstance instance;

    private final endive.testing.exports.example.exportedresources.logging.Guest logging;

    private ExportSomeResources(ComponentInstance instance) {
        this.instance = instance;
        this.logging = new endive.testing.exports.example.exportedresources.logging.Guest(instance.exportedInstance("example:exported-resources/logging"));
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static ExportSomeResources instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        return new ExportSomeResources(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The component instance behind these bindings.
     */
    public ComponentInstance instance() {
        return instance;
    }

    /**
     * The exported interface {@code example:exported-resources/logging}.
     */
    public endive.testing.exports.example.exportedresources.logging.Guest logging() {
        return logging;
    }
}
