package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostInstance;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:interface-imports/with-imports}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class WithImports {

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        /**
         * The imported interface {@code example:interface-imports/logging}.
         */
        endive.testing.example.interfaceimports.logging.Host logging();
    }

    private final ComponentInstance instance;

    private WithImports(ComponentInstance instance) {
        this.instance = instance;
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static WithImports instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        endive.testing.example.interfaceimports.logging.Host logging = imports.logging();
        HostInstance.Builder loggingBuilder = HostInstance.builder(store);
        ValType loggingLevel = loggingBuilder.declareType(Type.of(EnumType.builder().addLabel("debug").addLabel("info").addLabel("warn").addLabel("error").build()));
        loggingBuilder.addFunction("log", FuncType.builder().addParam(LabelValType.builder().withLabel("level").withValType(loggingLevel).build()).addParam(LabelValType.builder().withLabel("msg").withValType(ValType.builder().withPrimValType(PrimValType.STRING).build()).build()).build(), args -> {
            logging.log(endive.testing.example.interfaceimports.logging.Level.fromComponent(args[0]), (String) args[1]);
            return new Object[0];
        });
        values.put("example:interface-imports/logging", loggingBuilder.build());
        return new WithImports(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The component instance behind these bindings.
     */
    public ComponentInstance instance() {
        return instance;
    }
}
