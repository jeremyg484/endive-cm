package run.endive.cm.runtime;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.types.BorrowType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.OwnType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ResourceType;
import run.endive.cm.types.TupleType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;
import run.endive.log.Logger;
import run.endive.log.SystemLogger;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.TrapException;
import run.endive.tools.wasm.WasmToolsModule;
import run.endive.wasi.WasiExitException;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

public final class SpecTestImports {

    private static final Logger logger =
            new SystemLogger() {
                @Override
                public boolean isLoggable(Logger.Level level) {
                    return false;
                }
            };

    private static final WasmModule MODULE = WasmToolsModule.load();

    private static final Object[] EMPTY_RESULT = new Object[0];

    private SpecTestImports() {}

    public static Map<String, Object> build() {
        Map<String, Object> imports = new HashMap<>();
        imports.put("a", buildA());
        imports.put("host-return-two", buildHostReturnTwo());
        imports.put("foo", buildFoo());
        imports.put("src", buildSrc());
        imports.put("reexport", buildReexport());
        imports.put("provider", buildProvider());
        imports.put("host", buildHost());
        return Map.copyOf(imports);
    }

    private static Type buildA() {
        return Type.of(
                TupleType.builder()
                        .addElementType(ValType.builder().withPrimValType(PrimValType.U32).build())
                        .addElementType(ValType.builder().withPrimValType(PrimValType.U32).build())
                        .build());
    }

    private static ComponentFunction buildHostReturnTwo() {
        var store = new ComponentStore(WasmComponent.builder().build(), true);

        FuncType funcType =
                FuncType.builder()
                        .withResult(ValType.builder().withPrimValType(PrimValType.U32).build())
                        .build();

        ComponentFunctionCall call = (args) -> new Object[] {2};

        return ComponentFunctionInstance.builder()
                .withComponentStore(store)
                .withTypeResolver(store)
                .withFuncType(funcType)
                .withCall(call)
                .build();
    }

    private static ComponentInstance buildFoo() {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, true);
        WasmModule module = WasmModule.builder().build();
        store.addExport("a-module", module);
        return new ComponentInstance(store, definition);
    }

    private static ComponentInstance buildSrc() {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, true);
        WasmModule module = WasmModule.builder().build();
        store.addExport("m", module);
        return new ComponentInstance(store, definition);
    }

    private static ComponentInstance buildReexport() {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, true);

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

        store.addExport("m1", modules[0]);
        store.addExport("m2", modules[1]);
        store.addExport("m3", modules[2]);
        return new ComponentInstance(store, definition);
    }

    private static ComponentInstance buildProvider() {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, true);
        var moduleWat =
                "(module\n"
                        + "    (global (export \"g1\") i32 (i32.const 100))\n"
                        + "    (global (export \"g2\") i32 (i32.const 101))\n"
                        + "    (global (export \"g3\") i32 (i32.const 102))\n"
                        + "    (global (export \"g4\") i32 (i32.const 103))\n"
                        + ")";
        var module = moduleFromWat(moduleWat);
        store.addExport("m", module);
        return new ComponentInstance(store, definition);
    }

    /**
     * The {@code host} instance the resource spec tests import: one host-implemented resource
     * type, {@code resource1}, together with the constructor and methods those tests call, plus
     * a second resource type and a non-resource export used to check that mismatches are caught.
     *
     * <p>{@code resource1} is <em>host</em>-defined, so its destructor is a Java callback rather
     * than a core function. That is what makes {@code drops} and {@code last-drop} observable:
     * when a guest calls {@code resource.drop} on an owned handle, the drop lands here.
     */
    private static ComponentInstance buildHost() {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, true);
        var state = new Resource1State();
        var types = new HostTypes(store);

        Type resource1 = hostResourceType();
        store.declareHostResourceType(resource1, state::recordDrop);
        types.add(resource1); // 0
        ValType ownR1 = types.add(Type.of(OwnType.builder().withTypeIdx(0).build()));
        ValType borrowR1 = types.add(Type.of(BorrowType.builder().withTypeIdx(0).build()));

        // A second, distinct resource type. Its identity differs from resource1's even though
        // the two declarations are identical, which is what the "mismatched resource types"
        // test turns on.
        Type resource2 = hostResourceType();
        types.add(resource2);

        // `return-three` is deliberately not a resource, so that an import declaring it as one
        // is rejected with "expected resource found func".
        Type returnThree = Type.of(FuncType.builder().build());
        types.add(returnThree);

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

        store.addExport("resource1", resource1);
        store.addExport("resource2", resource2);
        store.addExport("resource1-again", resource1);
        store.addExport("return-three", returnThree);

        store.addExport(
                "[constructor]resource1",
                hostFunc(
                        store,
                        constructor,
                        args ->
                                new Object[] {
                                    ResourceValue.owned(store.resourceTypeAt(0), rep(args[0]))
                                }));

        // Taking an `own` argument consumes the caller's handle during lifting, so these two
        // leave the guest's table one entry shorter and never see a destructor run — a drop is
        // what `resource.drop` does, and that is what the counters below track.
        store.addExport(
                "[static]resource1.assert",
                hostFunc(store, staticAssert, args -> assertRep(args[0], args[1])));
        store.addExport(
                "[method]resource1.take-own", hostFunc(store, takeOwn, args -> EMPTY_RESULT));

        store.addExport(
                "[static]resource1.last-drop",
                hostFunc(store, u32Getter, args -> new Object[] {(long) state.lastDrop}));
        store.addExport(
                "[static]resource1.drops",
                hostFunc(store, u32Getter, args -> new Object[] {(long) state.drops}));

        store.addExport(
                "[method]resource1.simple",
                hostFunc(store, simple, args -> assertRep(args[0], args[1])));
        // Borrowing proves itself by the lifting that got us here: both handles were checked
        // against resource1 and marked lent for the duration of this call.
        store.addExport(
                "[method]resource1.take-borrow", hostFunc(store, takeBorrow, args -> EMPTY_RESULT));

        return new ComponentInstance(store, definition);
    }

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

        private final ComponentStore store;
        private int count;

        HostTypes(ComponentStore store) {
            this.store = store;
        }

        ValType add(Type type) {
            store.addType(type);
            return ValType.builder().withTypeIdx(count++).build();
        }
    }

    private static Type hostResourceType() {
        return Type.of(ResourceType.builder().withRep(run.endive.wasm.types.ValType.I32).build());
    }

    private static ComponentFunction hostFunc(
            ComponentStore store, FuncType funcType, ComponentFunctionCall call) {
        return ComponentFunctionInstance.builder()
                .withComponentStore(store)
                .withTypeResolver(store)
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
        try (var stdinStream = new ByteArrayInputStream(wat.getBytes());
                var stdoutStream = new ByteArrayOutputStream();
                var stderrStream = new ByteArrayOutputStream();
                FileSystem fs =
                        ZeroFs.newFileSystem(
                                Configuration.unix().toBuilder()
                                        .setAttributeViews("unix")
                                        .build())) {

            Path workDir = fs.getPath("/work");
            Files.createDirectories(workDir);

            var options =
                    WasiOptions.builder()
                            .withStdin(stdinStream, false)
                            .withStdout(stdoutStream, false)
                            .withStderr(stderrStream, false)
                            .withDirectory("/", workDir)
                            .withArguments(List.of("wasm-tools", "parse", "-o", "output.wasm"))
                            .build();

            try (var wasi =
                    WasiPreview1.builder().withLogger(logger).withOptions(options).build()) {
                var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

                try {
                    Instance.builder(MODULE)
                            .withMachineFactory(WasmToolsModule::create)
                            .withMemoryFactory(ByteArrayMemory::new)
                            .withImportValues(imports)
                            .build();
                } catch (WasiExitException e) {
                    if (e.exitCode() != 0) {
                        throw new WasmEngineException(
                                stdoutStream.toString(StandardCharsets.UTF_8)
                                        + stderrStream.toString(StandardCharsets.UTF_8),
                                e);
                    }
                }
            }

            try (Stream<Path> wasmFiles = Files.list(workDir)) {
                return wasmFiles
                        .map(
                                p -> {
                                    try {
                                        return Files.readAllBytes(p);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                })
                        .map(Parser::parse)
                        .findFirst()
                        .orElseThrow();
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
