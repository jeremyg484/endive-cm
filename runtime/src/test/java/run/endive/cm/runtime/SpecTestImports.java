package run.endive.cm.runtime;

import java.util.HashMap;
import java.util.Map;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.types.BorrowType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.OwnType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ResourceType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.runtime.TrapException;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

public final class SpecTestImports {

    private static final Object[] EMPTY_RESULT = new Object[0];

    private SpecTestImports() {}

    /**
     * The host definitions the spec suite imports, built into {@code store} so that they share
     * resource identities and handle tables with the components under test.
     */
    public static Map<String, Object> build(ComponentStore store) {
        Map<String, Object> imports = new HashMap<>();
        imports.put("host-return-two", buildHostReturnTwo(store));
        imports.put("foo", buildFoo(store));
        imports.put("src", buildSrc(store));
        imports.put("reexport", buildReexport(store));
        imports.put("provider", buildProvider(store));
        imports.put("host", buildHost(store));
        return Map.copyOf(imports);
    }

    /**
     * A bare host function, imported under a name of its own rather than through an instance. It
     * still belongs to an instance, which is what a call into it enters, so the instance is built
     * here even though nothing is exported from it.
     */
    private static ComponentFunction buildHostReturnTwo(ComponentStore store) {
        ComponentInstance instance = ComponentInstance.builder(store).build();

        FuncType funcType =
                FuncType.builder()
                        .withResult(ValType.builder().withPrimValType(PrimValType.U32).build())
                        .build();

        ComponentFunctionCall call = (args) -> new Object[] {2};

        return ComponentFunctionInstance.builder()
                .withInstance(instance)
                .withTypeResolver(instance)
                .withFuncType(funcType)
                .withCall(call)
                .withHostProvided(true)
                .build();
    }

    private static ComponentInstance buildFoo(ComponentStore store) {
        var builder = ComponentInstance.builder(store);
        WasmModule module = WasmModule.builder().build();
        builder.addExport("a-module", module);
        return builder.build();
    }

    private static ComponentInstance buildSrc(ComponentStore store) {
        var builder = ComponentInstance.builder(store);
        WasmModule module = WasmModule.builder().build();
        builder.addExport("m", module);
        return builder.build();
    }

    private static ComponentInstance buildReexport(ComponentStore store) {
        var builder = ComponentInstance.builder(store);

        var modules = new WasmModule[3];
        for (int i = 0; i < modules.length; i++) {
            var moduleWat =
                    "(module\n"
                            + "  (global (export \"g\") i32 (i32.const "
                            + (i + 1)
                            + "))\n"
                            + ")";
            modules[i] = moduleFromWat(moduleWat);
        }

        builder.addExport("m1", modules[0]);
        builder.addExport("m2", modules[1]);
        builder.addExport("m3", modules[2]);
        return builder.build();
    }

    private static ComponentInstance buildProvider(ComponentStore store) {
        var builder = ComponentInstance.builder(store);
        var moduleWat =
                "(module\n"
                        + "    (global (export \"g1\") i32 (i32.const 100))\n"
                        + "    (global (export \"g2\") i32 (i32.const 101))\n"
                        + "    (global (export \"g3\") i32 (i32.const 102))\n"
                        + "    (global (export \"g4\") i32 (i32.const 103))\n"
                        + ")";
        var module = moduleFromWat(moduleWat);
        builder.addExport("m", module);
        return builder.build();
    }

    /**
     * The {@code host} instance the resource spec tests import. It holds one host-implemented
     * resource type, {@code resource1}, together with the constructor and methods those tests call,
     * plus a second resource type and a non-resource export used to check that mismatches are
     * caught.
     *
     * <p>{@code resource1} is <em>host</em>-defined, so its destructor is a Java callback rather
     * than a core function. That is what makes {@code drops} and {@code last-drop} observable: when
     * a guest calls {@code resource.drop} on an owned handle, the drop lands here.
     */
    private static ComponentInstance buildHost(ComponentStore store) {
        var builder = ComponentInstance.builder(store);
        var state = new Resource1State();
        var types = new HostTypes(builder);

        Type resource1 = hostResourceType();
        ResourceTypeInstance rt1 = builder.declareHostResourceType(resource1, state::recordDrop);
        types.add(resource1, rt1); // 0
        ValType ownR1 = types.add(Type.of(OwnType.builder().withTypeIdx(0).build()));
        ValType borrowR1 = types.add(Type.of(BorrowType.builder().withTypeIdx(0).build()));

        // A second, distinct resource type. Its identity differs from resource1's even though
        // the two declarations are identical, which is what the "mismatched resource types"
        // test exercises.
        Type resource2 = hostResourceType();
        ResourceTypeInstance rt2 = builder.declareHostResourceType(resource2, null);
        types.add(resource2, rt2);

        FuncType constructor = func().addParam(param("r", u32())).withResult(ownR1).build();
        FuncType staticAssert =
                func().addParam(param("r", ownR1)).addParam(param("rep", u32())).build();
        FuncType u32Getter = func().withResult(u32()).build();
        FuncType simple =
                func().addParam(param("self", borrowR1)).addParam(param("rep", u32())).build();
        FuncType takeBorrow =
                func().addParam(param("self", borrowR1)).addParam(param("b", borrowR1)).build();
        FuncType takeOwn =
                func().addParam(param("self", borrowR1)).addParam(param("b", ownR1)).build();

        builder.addExport("resource1", rt1);
        builder.addExport("resource2", rt2);
        builder.addExport("resource1-again", rt1);

        // A function, deliberately not a resource, so that an import declaring it as one is
        // rejected with "expected resource found func", and callable, because another test
        // imports it for what it is and checks that it returns three.
        builder.addExport("return-three", hostFunc(builder, u32Getter, args -> new Object[] {3L}));
        builder.addExport("nested", buildNestedHost(store));
        builder.addExport("simple-module", moduleFromWat(SIMPLE_MODULE_WAT));

        builder.addExport(
                "[constructor]resource1",
                hostFunc(
                        builder,
                        constructor,
                        args ->
                                new Object[] {
                                    ResourceValue.owned(
                                            builder.instance().requireResourceType(0), rep(args[0]))
                                }));

        // Taking an `own` argument consumes the caller's handle during lifting, so these two
        // leave the guest's table one entry shorter and never see a destructor run, because a drop
        // is
        // what `resource.drop` does, and that is what the counters below track.
        builder.addExport(
                "[static]resource1.assert",
                hostFunc(builder, staticAssert, args -> assertRep(args[0], args[1])));
        builder.addExport(
                "[method]resource1.take-own", hostFunc(builder, takeOwn, args -> EMPTY_RESULT));

        builder.addExport(
                "[static]resource1.last-drop",
                hostFunc(builder, u32Getter, args -> new Object[] {(long) state.lastDrop}));
        builder.addExport(
                "[static]resource1.drops",
                hostFunc(builder, u32Getter, args -> new Object[] {(long) state.drops}));

        builder.addExport(
                "[method]resource1.simple",
                hostFunc(builder, simple, args -> assertRep(args[0], args[1])));
        // Borrowing proves itself by the lifting that got us here, since both handles were checked
        // against resource1 and marked lent for the duration of this call.
        builder.addExport(
                "[method]resource1.take-borrow",
                hostFunc(builder, takeBorrow, args -> EMPTY_RESULT));

        return builder.build();
    }

    /**
     * An instance nested inside {@code host}, so that a component can import an instance that
     * itself exports one and reach through both to call {@code return-four}.
     */
    private static ComponentInstance buildNestedHost(ComponentStore store) {
        var builder = ComponentInstance.builder(store);
        builder.addExport(
                "return-four",
                hostFunc(builder, func().withResult(u32()).build(), args -> new Object[] {4L}));
        return builder.build();
    }

    /**
     * A core module {@code host} hands out whole, for components that instantiate it themselves
     * rather than calling into it. The two values are what the importing test asserts.
     */
    private static final String SIMPLE_MODULE_WAT =
            "(module\n"
                    + "  (func (export \"f\") (result i32) (i32.const 101))\n"
                    + "  (global (export \"g\") i32 (i32.const 100))\n"
                    + ")";

    /** Drop bookkeeping for {@code resource1}, observable through its static methods. */
    private static final class Resource1State {

        private int drops;
        private int lastDrop;

        void recordDrop(int rep) {
            drops++;
            lastDrop = rep;
        }
    }

    /**
     * Hands out consecutive indices as types are added, so that a {@code ValType} naming one is
     * built from the index it actually landed at rather than a hand-counted guess.
     */
    private static final class HostTypes {

        private final ComponentInstance.Builder builder;
        private int count;

        HostTypes(ComponentInstance.Builder builder) {
            this.builder = builder;
        }

        ValType add(Type type) {
            return add(type, null);
        }

        ValType add(Type type, ResourceTypeInstance resourceType) {
            builder.addType(type, resourceType);
            return ValType.builder().withTypeIdx(count++).build();
        }
    }

    private static Type hostResourceType() {
        return Type.of(ResourceType.builder().withRep(run.endive.wasm.types.ValType.I32).build());
    }

    private static ComponentFunction hostFunc(
            ComponentInstance.Builder builder, FuncType funcType, ComponentFunctionCall call) {
        return ComponentFunctionInstance.builder()
                .withInstance(builder.instance())
                .withTypeResolver(builder.instance())
                .withFuncType(funcType)
                .withCall(call)
                .withHostProvided(true)
                .build();
    }

    /** Checks a handle's representation against what the caller says it should be. */
    private static Object[] assertRep(Object handle, Object expected) {
        int actual = ((ResourceValue) handle).rep();
        int want = rep(expected);
        if (actual != want) {
            throw new TrapException("expected resource rep " + want + " but got " + actual);
        }
        return EMPTY_RESULT;
    }

    private static int rep(Object u32) {
        return ((Number) u32).intValue();
    }

    private static FuncType.Builder func() {
        return FuncType.builder();
    }

    private static ValType u32() {
        return ValType.builder().withPrimValType(PrimValType.U32).build();
    }

    private static LabelValType param(String label, ValType valType) {
        return LabelValType.builder().withLabel(label).withValType(valType).build();
    }

    private static WasmModule moduleFromWat(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }
}
