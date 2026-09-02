package endive.testing.exports.environment;

import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;
import run.endive.cm.runtime.VoidHostTypeDescriptor;

/**
 * The WIT interface {@code environment}, as the component exports it.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class Guest {

    private final ComponentFunction get;

    private final ComponentFunction set;

    /**
     * Built by the world's bindings. Public only because they are another package.
     */
    public Guest(ComponentInstance instance) {
        this.get = instance.export("get").typed(PrimitiveHostTypeDescriptor.forClass(String.class), PrimitiveHostTypeDescriptor.forClass(String.class));
        this.set = instance.export("set").typed(VoidHostTypeDescriptor.instance(), PrimitiveHostTypeDescriptor.forClass(String.class), PrimitiveHostTypeDescriptor.forClass(String.class));
    }

    public String get(String var) {
        return (String) this.get.apply(var)[0];
    }

    public void set(String var, String val) {
        this.set.apply(var, val);
    }
}
