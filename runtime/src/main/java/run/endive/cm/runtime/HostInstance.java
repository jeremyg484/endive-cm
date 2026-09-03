package run.endive.cm.runtime;

import java.util.function.IntConsumer;
import run.endive.cm.types.BorrowType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.OwnType;
import run.endive.cm.types.ResourceType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.wasm.WasmModule;

/**
 * Builds a component instance the embedder supplies, for satisfying an import with Java rather than
 * with another component's definitions.
 *
 * <p>Such an instance still needs a type index space of its own. A function type naming a record or
 * a resource carries an index, and that index resolves against the instance the function belongs
 * to, so the types have to be declared before the functions mentioning them.
 *
 * <p>Use {@link HostFunction} instead for an import declared as a bare function, which belongs to
 * no instance the importer can name.
 */
public final class HostInstance {

    private HostInstance() {}

    public static Builder builder(ComponentStore store) {
        return new Builder(store);
    }

    /**
     * Declarations and exports go in through here, in an order of the caller's choosing except that
     * a type has to be declared before anything names it. The instance is sealed by {@link #build}.
     */
    public static final class Builder {

        private final ComponentInstance.Builder delegate;

        private Builder(ComponentStore store) {
            this.delegate = ComponentInstance.builder(store);
        }

        /**
         * Appends {@code type} to this instance's type index space.
         *
         * @return a value type naming the index it landed at
         */
        public ValType declareType(Type type) {
            return declareType(type, null);
        }

        private ValType declareType(Type type, ResourceTypeInstance resourceType) {
            int index = delegate.instance().typeCount();
            delegate.addType(type, resourceType);
            return ValType.builder().withTypeIdx(index).build();
        }

        /**
         * Brings a resource type the host implements into existence and declares it, along with its
         * {@code own} and {@code borrow}.
         *
         * @param destructor run when an owning handle is dropped, {@code null} when dropping one
         *     does nothing observable
         */
        public HostResource declareResource(IntConsumer destructor) {
            Type declaration =
                    Type.of(
                            ResourceType.builder()
                                    .withRep(run.endive.wasm.types.ValType.I32)
                                    .build());
            ResourceTypeInstance resourceType =
                    delegate.declareHostResourceType(declaration, destructor);
            int typeIdx = declareType(declaration, resourceType).typeIdx();
            return new HostResource(
                    resourceType,
                    declareType(Type.of(OwnType.builder().withTypeIdx(typeIdx).build())),
                    declareType(Type.of(BorrowType.builder().withTypeIdx(typeIdx).build())));
        }

        /** Exports a function implemented in Java, of the type against which an importer matches. */
        public Builder addFunction(String name, FuncType funcType, ComponentFunctionCall call) {
            delegate.addExport(name, function(delegate, funcType, call));
            return this;
        }

        /** Exports a resource type, which may be exported under more than one name. */
        public Builder addResource(String name, HostResource resource) {
            delegate.addExport(name, resource.instance());
            return this;
        }

        /** Exports an instance, so that an importer can reach through this one to its exports. */
        public Builder addInstance(String name, ComponentInstance instance) {
            delegate.addExport(name, instance);
            return this;
        }

        /** Exports a core module, for an importer that instantiates it rather than calling it. */
        public Builder addModule(String name, WasmModule module) {
            delegate.addExport(name, module);
            return this;
        }

        /** Seals the instance and opens it to calls. */
        public ComponentInstance build() {
            return delegate.build();
        }
    }

    /**
     * Builds a host function against {@code owner}, which is the instance a call enters and the
     * space against which its type resolves. Shared with {@link HostFunction}.
     */
    static ComponentFunction function(
            ComponentInstance.Builder owner, FuncType funcType, ComponentFunctionCall call) {
        return ComponentFunctionInstance.builder()
                .withInstance(owner.instance())
                .withTypeResolver(owner.instance())
                .withFuncType(funcType)
                .withCall(call)
                .withHostProvided(true)
                .build();
    }
}
