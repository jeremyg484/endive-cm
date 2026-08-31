package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.abi.VariantValue;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.GuestResource;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;
import run.endive.cm.runtime.ResourceHostTypeDescriptor;
import run.endive.cm.runtime.VariantHostTypeDescriptor;
import run.endive.cm.runtime.VoidHostTypeDescriptor;
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

    /**
     * The exported interface {@code example:exported-resources/logging}.
     */
    public static final class Logging {

        /**
         * The enum {@code level}.
         */
        enum Level {

            DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

            private final String label;

            Level(String label) {
                this.label = label;
            }

            /**
             * This case as the ABI carries it, which is a variant with no payload.
             */
            public VariantValue toComponent() {
                return VariantValue.of(label, null);
            }

            /**
             * The case a lifted value names.
             */
            public static Level fromComponent(Object value) {
                String label = ((VariantValue) value).label();
                for (Level candidate : values()) {
                    if (candidate.label.equals(label)) {
                        return candidate;
                    }
                }
                throw new IllegalArgumentException("unknown level: " + label);
            }
        }

        /**
         * The exported resource {@code logger}.
         */
        public static final class Logger implements AutoCloseable {

            private final Logging owner;

            private final ResourceValue handle;

            private boolean dropped;

            private Logger(Logging owner, ResourceValue handle) {
                this.owner = owner;
                this.handle = handle;
            }

            public Logging.Level getMaxLevel() {
                return Logging.Level.fromComponent(owner.loggerGetMaxLevel.apply(handle)[0]);
            }

            public void setMaxLevel(Logging.Level level) {
                owner.loggerSetMaxLevel.apply(handle, level.toComponent());
            }

            public void log(Logging.Level level, String msg) {
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

        private final ComponentFunction loggerLogger;

        private final ComponentFunction loggerGetMaxLevel;

        private final ComponentFunction loggerSetMaxLevel;

        private final ComponentFunction loggerLog;

        private Logging(ComponentInstance instance) {
            this.loggerLogger = instance.export("[constructor]logger").typed(ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance());
            this.loggerGetMaxLevel = instance.export("[method]logger.get-max-level").typed(VariantHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance());
            this.loggerSetMaxLevel = instance.export("[method]logger.set-max-level").typed(VoidHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance());
            this.loggerLog = instance.export("[method]logger.log").typed(VoidHostTypeDescriptor.instance(), ResourceHostTypeDescriptor.instance(), VariantHostTypeDescriptor.instance(), PrimitiveHostTypeDescriptor.forClass(String.class));
        }

        /**
         * Makes a {@code logger} inside the component.
         */
        public Logger logger(Logging.Level maxLevel) {
            return new Logger(this, (ResourceValue) this.loggerLogger.apply(maxLevel.toComponent())[0]);
        }
    }

    private final ComponentInstance instance;

    private final Logging logging;

    private ExportSomeResources(ComponentInstance instance) {
        this.instance = instance;
        this.logging = new Logging(instance.exportedInstance("example:exported-resources/logging"));
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static ExportSomeResources instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        return new ExportSomeResources(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The instance these bindings call into.
     */
    public ComponentInstance instance() {
        return instance;
    }

    /**
     * The exported interface {@code example:exported-resources/logging}.
     */
    public Logging logging() {
        return logging;
    }
}
