package endive.testing.exports.example.exportedresources.logging;

import javax.annotation.processing.Generated;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;
import run.endive.cm.runtime.ResourceHostTypeDescriptor;
import run.endive.cm.runtime.VariantHostTypeDescriptor;
import run.endive.cm.runtime.VoidHostTypeDescriptor;

/**
 * The WIT interface {@code example:exported-resources/logging}, as the component exports it.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class Guest {

    final ComponentFunction loggerLogger;

    final ComponentFunction loggerGetMaxLevel;

    final ComponentFunction loggerSetMaxLevel;

    final ComponentFunction loggerLog;

    /**
     * Built by the world's bindings. Public only because they are another package.
     */
    public Guest(ComponentInstance instance) {
        this.loggerLogger = instance.export("[constructor]logger").typed(ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance());
        this.loggerGetMaxLevel = instance.export("[method]logger.get-max-level").typed(VariantHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance());
        this.loggerSetMaxLevel = instance.export("[method]logger.set-max-level").typed(VoidHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance());
        this.loggerLog = instance.export("[method]logger.log").typed(VoidHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance(), PrimitiveHostTypeDescriptor.forClass(String.class));
    }

    /**
     * Makes a {@code logger} inside the component.
     */
    public Logger logger(Level maxLevel) {
        return new Logger(this, (ResourceValue) this.loggerLogger.apply(maxLevel.toComponent())[0]);
    }
}
