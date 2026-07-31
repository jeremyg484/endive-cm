package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ValType;
import run.endive.wasm.WasmEngineException;

public final class ComponentFunctionInstance implements ComponentFunction {

    private final ComponentStore store;
    private final FuncType funcType;
    private final ComponentFunctionCall call;

    public ComponentFunctionInstance(
            ComponentStore store, FuncType funcType, ComponentFunctionCall call) {
        this.store = store;
        this.funcType = funcType;
        this.call = call;
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
        public ComponentFunction typed(
                HostTypeDescriptor resultsDescriptor, HostTypeDescriptor... paramDescriptors) {
            throw new UnsupportedOperationException("A typed function cannot be re-cast");
        }
    }
}
