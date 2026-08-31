package endive.testing.exports.example.worldexports.units;

import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;

/**
 * The WIT interface {@code example:world-exports/units}, as the component exports it.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class Guest {

    private final ComponentFunction bytesToString;

    private final ComponentFunction durationToString;

    /**
     * Built by the world's bindings. Public only because they are another package.
     */
    public Guest(ComponentInstance instance) {
        this.bytesToString = instance.export("bytes-to-string").typed(PrimitiveHostTypeDescriptor.forClass(String.class), PrimitiveHostTypeDescriptor.forClass(Long.class));
        this.durationToString = instance.export("duration-to-string").typed(PrimitiveHostTypeDescriptor.forClass(String.class), PrimitiveHostTypeDescriptor.forClass(Long.class), PrimitiveHostTypeDescriptor.forClass(Long.class));
    }

    public String bytesToString(Long bytes) {
        return (String) this.bytesToString.apply(bytes)[0];
    }

    public String durationToString(Long seconds, Long ns) {
        return (String) this.durationToString.apply(seconds, ns)[0];
    }
}
