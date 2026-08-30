package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostInstance;
import run.endive.cm.runtime.PrimitiveHostTypeDescriptor;
import run.endive.cm.runtime.VoidHostTypeDescriptor;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code my:project/exports-world}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class ExportsWorld {

    private static final FuncType HOST_GEN_RANDOM_INTEGER_FUNC = FuncType.builder().withResult(ValType.builder().withPrimValType(PrimValType.U32).build()).build();

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        /**
         * The imported interface {@code my:project/host}.
         */
        Host host();
    }

    /**
     * The imported interface {@code my:project/host}.
     */
    public interface Host {

        Long genRandomInteger();
    }

    /**
     * The exported interface {@code demo}.
     */
    public static final class Demo {

        private final ComponentFunction run;

        private Demo(ComponentInstance instance) {
            this.run = instance.export("run").typed(VoidHostTypeDescriptor.instance());
        }

        public void run() {
            this.run.apply();
        }
    }

    private final ComponentInstance instance;

    private final ComponentFunction add;

    private final ComponentFunction countBytes;

    private final Demo demo;

    private ExportsWorld(ComponentInstance instance) {
        this.instance = instance;
        this.add = instance.export("add").typed(PrimitiveHostTypeDescriptor.forClass(Long.class), PrimitiveHostTypeDescriptor.forClass(Long.class), PrimitiveHostTypeDescriptor.forClass(Long.class));
        this.countBytes = instance.export("count-bytes").typed(PrimitiveHostTypeDescriptor.forClass(Long.class), PrimitiveHostTypeDescriptor.forClass(String.class));
        this.demo = new Demo(instance.exportedInstance("demo"));
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static ExportsWorld instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        Host host = imports.host();
        values.put("my:project/host", HostInstance.builder(store).addFunction("gen-random-integer", HOST_GEN_RANDOM_INTEGER_FUNC, args -> new Object[] { host.genRandomInteger() }).build());
        return new ExportsWorld(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The instance these bindings call into.
     */
    public ComponentInstance instance() {
        return instance;
    }

    public Long add(Long a, Long b) {
        return (Long) this.add.apply(a, b)[0];
    }

    public Long countBytes(String s) {
        return (Long) this.countBytes.apply(s)[0];
    }

    /**
     * The exported interface {@code demo}.
     */
    public Demo demo() {
        return demo;
    }
}
