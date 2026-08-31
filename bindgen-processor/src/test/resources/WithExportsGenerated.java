package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostFunction;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;
import run.endive.cm.runtime.VoidHostTypeDescriptor;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:world-exports/with-exports}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class WithExports {

    private static final FuncType LOG_FUNC = FuncType.builder().addParam(LabelValType.builder().withLabel("msg").withValType(ValType.builder().withPrimValType(PrimValType.STRING).build()).build()).build();

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        void log(String msg);
    }

    /**
     * The exported interface {@code environment}.
     */
    public static final class Environment {

        private final ComponentFunction get;

        private final ComponentFunction set;

        private Environment(ComponentInstance instance) {
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

    /**
     * The exported interface {@code example:world-exports/units}.
     */
    public static final class Units {

        private final ComponentFunction bytesToString;

        private final ComponentFunction durationToString;

        private Units(ComponentInstance instance) {
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

    private final ComponentInstance instance;

    private final ComponentFunction run;

    private final Environment environment;

    private final Units units;

    private WithExports(ComponentInstance instance) {
        this.instance = instance;
        this.run = instance.export("run").typed(VoidHostTypeDescriptor.instance());
        this.environment = new Environment(instance.exportedInstance("environment"));
        this.units = new Units(instance.exportedInstance("example:world-exports/units"));
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static WithExports instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("log", HostFunction.of(store, LOG_FUNC, args -> {
            imports.log((String) args[0]);
            return new Object[0];
        }));
        return new WithExports(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The instance these bindings call into.
     */
    public ComponentInstance instance() {
        return instance;
    }

    public void run() {
        this.run.apply();
    }

    /**
     * The exported interface {@code environment}.
     */
    public Environment environment() {
        return environment;
    }

    /**
     * The exported interface {@code example:world-exports/units}.
     */
    public Units units() {
        return units;
    }
}
