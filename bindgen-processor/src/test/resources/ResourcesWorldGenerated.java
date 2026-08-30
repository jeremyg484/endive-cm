package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.runtime.ComponentFunction;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostInstance;
import run.endive.cm.runtime.HostResource;
import run.endive.cm.runtime.HostResourceTable;
import run.endive.cm.runtime.VoidHostTypeDescriptor;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:resources/resources-world}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class ResourcesWorld {

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        /**
         * The imported interface {@code types}.
         */
        Types types();
    }

    /**
     * The imported interface {@code types}.
     */
    public interface Types {

        /**
         * The resource {@code file}.
         */
        interface File {

            String getName();

            Boolean isOpen();

            /**
             * Called when the guest drops an owned handle to this resource.
             */
            default void drop() {
            }
        }

        /**
         * Makes a {@code file}.
         */
        File file(String name);
    }

    private final ComponentInstance instance;

    private final ComponentFunction run;

    private ResourcesWorld(ComponentInstance instance) {
        this.instance = instance;
        this.run = instance.export("run").typed(VoidHostTypeDescriptor.instance());
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static ResourcesWorld instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        Types types = imports.types();
        HostInstance.Builder typesBuilder = HostInstance.builder(store);
        HostResourceTable<Types.File> typesFileTable = new HostResourceTable<>();
        HostResource typesFile = typesBuilder.declareResource(rep -> typesFileTable.drop(rep, Types.File::drop));
        typesBuilder.addResource("file", typesFile);
        typesBuilder.addFunction("[constructor]file", FuncType.builder().addParam(LabelValType.builder().withLabel("name").withValType(ValType.builder().withPrimValType(PrimValType.STRING).build()).build()).withResult(typesFile.own()).build(), args -> new Object[] { ResourceValue.owned(typesFile.type(), typesFileTable.add(types.file((String) args[0]))) });
        typesBuilder.addFunction("[method]file.get-name", FuncType.builder().addParam(LabelValType.builder().withLabel("self").withValType(typesFile.borrow()).build()).withResult(ValType.builder().withPrimValType(PrimValType.STRING).build()).build(), args -> new Object[] { typesFileTable.get((ResourceValue) args[0]).getName() });
        typesBuilder.addFunction("[method]file.is-open", FuncType.builder().addParam(LabelValType.builder().withLabel("self").withValType(typesFile.borrow()).build()).withResult(ValType.builder().withPrimValType(PrimValType.BOOL).build()).build(), args -> new Object[] { typesFileTable.get((ResourceValue) args[0]).isOpen() });
        values.put("types", typesBuilder.build());
        return new ResourcesWorld(ComponentLinker.builder().build().instantiate(store, component, values));
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
}
