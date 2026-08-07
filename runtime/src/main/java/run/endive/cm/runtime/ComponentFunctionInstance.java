package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import run.endive.cm.abi.LiftLowerContext;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.ValType;
import run.endive.wasm.WasmEngineException;

public final class ComponentFunctionInstance implements ComponentFunction {

    private final ComponentStore store;
    private final FuncType funcType;
    private final TypeResolver typeResolver;
    private final ComponentFunctionCall call;
    private final LiftLowerContext liftLowerContext;
    private final CoreFunction<?> liftedFunction;
    private final boolean hostProvided;

    private ComponentFunctionInstance(
            ComponentStore store,
            FuncType funcType,
            TypeResolver typeResolver,
            ComponentFunctionCall call,
            LiftLowerContext liftLowerContext,
            CoreFunction<?> liftedFunction,
            boolean hostProvided) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(funcType, "funcType");
        Objects.requireNonNull(typeResolver, "typeResolver");
        Objects.requireNonNull(call, "call");
        this.store = store;
        this.funcType = funcType;
        this.typeResolver = typeResolver;
        this.call = call;
        this.liftLowerContext = liftLowerContext;
        this.liftedFunction = liftedFunction;
        this.hostProvided = hostProvided;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ComponentStore componentStore;
        private FuncType funcType;
        private TypeResolver typeResolver;
        private ComponentFunctionCall call;
        private LiftLowerContext liftLowerContext;
        private CoreFunction<?> liftedFunction;
        private boolean hostProvided;

        public Builder withComponentStore(ComponentStore componentStore) {
            this.componentStore = componentStore;
            return this;
        }

        public Builder withFuncType(FuncType funcType) {
            this.funcType = funcType;
            return this;
        }

        public Builder withTypeResolver(TypeResolver typeResolver) {
            this.typeResolver = typeResolver;
            return this;
        }

        public Builder withCall(ComponentFunctionCall call) {
            this.call = call;
            return this;
        }

        public Builder withLiftLowerContext(LiftLowerContext liftLowerContext) {
            this.liftLowerContext = liftLowerContext;
            return this;
        }

        public Builder withLiftedFunction(CoreFunction<?> liftedFunction) {
            this.liftedFunction = liftedFunction;
            return this;
        }

        /** Marks this as an embedder-supplied function; see {@link #hostProvided()}. */
        public Builder withHostProvided(boolean hostProvided) {
            this.hostProvided = hostProvided;
            return this;
        }

        public ComponentFunctionInstance build() {
            return new ComponentFunctionInstance(
                    componentStore,
                    funcType,
                    typeResolver,
                    call,
                    liftLowerContext,
                    liftedFunction,
                    hostProvided);
        }
    }

    @Override
    public Object[] apply(Object... args) {
        var params = funcType.params();
        if (args.length != params.size()) {
            throw new WasmEngineException(
                    "Mismatch between number of arguments ("
                            + args.length
                            + ") and number of parameters ("
                            + params.size()
                            + ") in the component function. Expected params "
                            + params);
        }

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            LabelValType param = params.get(i);
            if (!isTypeCompatible(store, arg.getClass(), param.valType())) {
                throw new WasmEngineException(
                        "Argument '"
                                + arg
                                + "' at position "
                                + i
                                + " is not compatible with parameter "
                                + param);
            }
        }
        return call.apply(args);
    }

    @Override
    public FuncType funcType() {
        return funcType;
    }

    @Override
    public TypeResolver typeResolver() {
        return typeResolver;
    }

    @Override
    public boolean hostProvided() {
        return hostProvided;
    }

    @Override
    public boolean isLifted() {
        return liftLowerContext != null;
    }

    @Override
    public LiftLowerContext context() {
        return liftLowerContext;
    }

    @Override
    public CoreFunction<?> liftedFunction() {
        return liftedFunction;
    }

    @Override
    public ComponentFunction typed(
            HostTypeDescriptor resultDescriptor, HostTypeDescriptor... paramDescriptors) {
        return new ComponentFunctionInstance.Typed(resultDescriptor, paramDescriptors);
    }

    static boolean isTypeCompatible(
            ComponentStore store, Class<?> hostType, ValType componentType) {
        if (PrimitiveHostTypeDescriptor.supports(hostType)) {
            return isTypeCompatible(
                    store, PrimitiveHostTypeDescriptor.forClass(hostType), componentType);
        } else if (EnumHostTypeDescriptor.supports(hostType)) {
            return isTypeCompatible(
                    store, EnumHostTypeDescriptor.forClass(hostType), componentType);
        } else if (ResourceHostTypeDescriptor.supports(hostType)) {
            return isTypeCompatible(store, ResourceHostTypeDescriptor.instance(), componentType);
        }
        throw new IllegalArgumentException(
                "host type " + hostType.getName() + " is not yet supported");
    }

    static boolean isTypeCompatible(
            ComponentStore store, HostTypeDescriptor hostType, ValType componentType) {
        return hostType.isCompatibleWith(store, componentType);
    }

    public final class Typed implements ComponentFunction {

        private final List<HostTypeDescriptor> requiredParamTypes = new ArrayList<>();
        private final HostTypeDescriptor requiredResultType;

        private Typed(HostTypeDescriptor resultDescriptor, HostTypeDescriptor... paramDescriptors) {
            Objects.requireNonNull(resultDescriptor, "resultDescriptor");
            Objects.requireNonNull(paramDescriptors, "paramDescriptors");
            this.requiredResultType = resultDescriptor;
            this.requiredParamTypes.addAll(List.copyOf(requiredParamTypes));

            var result = funcType.result();
            if (!isTypeCompatible(store, resultDescriptor, result)) {
                throw new LinkageException(
                        "Result type descriptor "
                                + resultDescriptor
                                + " is not compatible with the result type "
                                + result
                                + " of the component function.");
            }

            var params = funcType.params();
            if (paramDescriptors.length != params.size()) {
                throw new LinkageException(
                        "Mismatch between number of type descriptors ("
                                + paramDescriptors.length
                                + ") and number of parameters ("
                                + params.size()
                                + ") in the component function. Expected params "
                                + params);
            }

            for (int i = 0; i < paramDescriptors.length; i++) {
                LabelValType param = params.get(i);
                if (isTypeCompatible(store, paramDescriptors[i], param.valType())) {
                    throw new WasmEngineException(
                            "Type descriptor "
                                    + paramDescriptors[i]
                                    + " at position "
                                    + i
                                    + " is not compatible with parameter "
                                    + param);
                }
            }
        }

        @Override
        public Object[] apply(Object... args) {
            for (int i = 0; i < requiredParamTypes.size(); i++) {
                var descriptor = requiredParamTypes.get(i);
                if (!descriptor.matches(args[i].getClass())) {
                    throw new WasmEngineException(
                            "Argument "
                                    + i
                                    + " does not match the type expected by parameter descriptor "
                                    + descriptor
                                    + "for parameter "
                                    + funcType.params().get(i));
                }
            }
            var result = call.apply(args);
            if (!requiredResultType.matches(result.getClass())) {
                throw new WasmEngineException(
                        "Result type "
                                + result.getClass()
                                + " does not match the type expected by result descriptor "
                                + requiredResultType);
            }
            return result;
        }

        @Override
        public FuncType funcType() {
            return funcType;
        }

        @Override
        public TypeResolver typeResolver() {
            return typeResolver;
        }

        @Override
        public boolean isLifted() {
            return false;
        }

        @Override
        public LiftLowerContext context() {
            return null;
        }

        @Override
        public CoreFunction<?> liftedFunction() {
            return null;
        }

        @Override
        public ComponentFunction typed(
                HostTypeDescriptor resultsDescriptor, HostTypeDescriptor... paramDescriptors) {
            throw new UnsupportedOperationException("A typed function cannot be re-cast");
        }
    }
}
