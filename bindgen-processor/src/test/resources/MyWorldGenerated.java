package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostFunction;
import run.endive.cm.runtime.HostInstance;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:imports/my-world}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class MyWorld {

    private static final FuncType GREET_FUNC = FuncType.builder().withResult(ValType.builder().withPrimValType(PrimValType.STRING).build()).build();

    private static final FuncType LOG_FUNC = FuncType.builder().addParam(LabelValType.builder().withLabel("msg").withValType(ValType.builder().withPrimValType(PrimValType.STRING).build()).build()).build();

    private static final FuncType MY_CUSTOM_HOST_TICK_FUNC = FuncType.builder().build();

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        String greet();

        void log(String msg);

        /**
         * The imported interface {@code my-custom-host}.
         */
        MyCustomHost myCustomHost();
    }

    /**
     * The imported interface {@code my-custom-host}.
     */
    public interface MyCustomHost {

        void tick();
    }

    private final ComponentInstance instance;

    private MyWorld(ComponentInstance instance) {
        this.instance = instance;
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static MyWorld instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("greet", HostFunction.of(store, GREET_FUNC, args -> new Object[] { imports.greet() }));
        values.put("log", HostFunction.of(store, LOG_FUNC, args -> {
            imports.log((String) args[0]);
            return new Object[0];
        }));
        MyCustomHost myCustomHost = imports.myCustomHost();
        values.put("my-custom-host", HostInstance.builder(store).addFunction("tick", MY_CUSTOM_HOST_TICK_FUNC, args -> {
            myCustomHost.tick();
            return new Object[0];
        }).build());
        return new MyWorld(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The instance these bindings call into.
     */
    public ComponentInstance instance() {
        return instance;
    }
}
