package endive.testing.exports.demo;

import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.VoidHostTypeDescriptor;

/**
 * The WIT interface {@code demo}, as the component exports it.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class Guest {

    private final ComponentFunction run;

    /**
     * Built by the world's bindings. Public only because they are another package.
     */
    public Guest(ComponentInstance instance) {
        this.run = instance.export("run").typed(VoidHostTypeDescriptor.instance());
    }

    public void run() {
        this.run.apply();
    }
}
