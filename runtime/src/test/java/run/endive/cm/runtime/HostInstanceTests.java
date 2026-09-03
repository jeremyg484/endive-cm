package run.endive.cm.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.WasmModule;

/**
 * The facade an embedder builds host-provided imports through, which is also what generated
 * bindings target. It exists so that supplying an import needs no access to the index-space
 * mechanics behind {@link ComponentInstance.Builder}.
 */
public class HostInstanceTests {

    /**
     * The shape a world's top-level function import takes, and the first thing bindgen has to
     * produce. A bare function belongs to no instance the importer can name.
     */
    @Test
    public void aBareHostFunctionCarriesItsResultBack() {
        ComponentFunction name =
                HostFunction.of(
                        new ComponentStore(),
                        func().withResult(string()).build(),
                        args -> new Object[] {"world"});

        assertArrayEquals(new Object[] {"world"}, name.apply());
        assertTrue(name.hostProvided());
    }

    /** A host function satisfies a root import declared with the same type. */
    @Test
    public void aBareHostFunctionSatisfiesARootImport() {
        var store = new ComponentStore();
        ComponentFunction hostFunc =
                HostFunction.of(
                        store,
                        func().addParam(param("x", u32())).withResult(u32()).build(),
                        args -> new Object[] {0L});

        var component =
                TestComponents.parse(
                        Wat2Wasm.parse(
                                "(component\n"
                                        + "  (import \"host-func\" (func (param \"x\" u32) (result"
                                        + " u32)))\n"
                                        + ")"));

        assertNotNull(
                ComponentLinker.builder()
                        .build()
                        .instantiate(store, component, Map.of("host-func", hostFunc)));
    }

    /**
     * A function type may name a type this instance declares, which is why a host instance carries
     * an index space at all. The index has to resolve, or the function cannot be matched or called.
     */
    @Test
    public void aDeclaredTypeResolvesFromAFunctionThatNamesIt() {
        var builder = HostInstance.builder(new ComponentStore());
        ValType point =
                builder.declareType(
                        Type.of(
                                RecordType.builder()
                                        .addField(param("x", u32()))
                                        .addField(param("y", u32()))
                                        .build()));

        builder.addFunction(
                "origin-distance",
                func().addParam(param("p", point)).withResult(u32()).build(),
                args -> new Object[] {0L});

        ComponentInstance host = builder.build();
        ComponentFunction distance = host.export("origin-distance");

        assertNotNull(distance.resolvedFuncType());
        assertArrayEquals(new Object[] {0L}, distance.apply(Map.of("x", 3L, "y", 4L)));
    }

    /** Types land at consecutive indices, so a later one can name an earlier one. */
    @Test
    public void declaredTypesAreNumberedInOrder() {
        var builder = HostInstance.builder(new ComponentStore());

        assertEquals(0, builder.declareType(Type.of(RecordType.builder().build())).typeIdx());
        assertEquals(1, builder.declareType(Type.of(RecordType.builder().build())).typeIdx());
    }

    /**
     * Resource types are generative, so two declarations that read alike are still two types. The
     * spec suite leans on this to check that a handle minted from one is refused by the other.
     */
    @Test
    public void twoDeclaredResourcesAreDistinctTypes() {
        var builder = HostInstance.builder(new ComponentStore());

        HostResource first = builder.declareResource(null);
        HostResource second = builder.declareResource(null);

        assertNotSame(first.type(), second.type());
        assertNotSame(first.own(), second.own());
    }

    /** A resource may be exported under more than one name, naming one type both times. */
    @Test
    public void oneResourceExportedTwiceIsOneType() {
        var builder = HostInstance.builder(new ComponentStore());
        HostResource resource = builder.declareResource(null);

        ComponentInstance host =
                builder.addResource("conn", resource).addResource("conn-again", resource).build();

        assertSame(host.getExport("conn"), host.getExport("conn-again"));
    }

    /** The destructor runs when the host itself is handed a dropped representation. */
    @Test
    public void aResourceDestructorReceivesTheRepresentation() {
        var dropped = new int[] {-1};
        var builder = HostInstance.builder(new ComponentStore());
        HostResource resource = builder.declareResource(rep -> dropped[0] = rep);

        builder.addFunction(
                "make",
                func().addParam(param("rep", u32())).withResult(resource.own()).build(),
                args -> new Object[] {ResourceValue.owned(resource.type(), 7)});
        ComponentInstance host = builder.build();

        Object[] result = host.export("make").apply(3L);
        assertEquals(7, ((ResourceValue) result[0]).rep());
        assertEquals(-1, dropped[0]);
    }

    /** An instance nested inside another, through which an importer reaches. */
    @Test
    public void anInstanceMayExportAnotherInstance() {
        var store = new ComponentStore();
        ComponentInstance inner =
                HostInstance.builder(store)
                        .addFunction(
                                "deep", func().withResult(u32()).build(), args -> new Object[] {4L})
                        .build();

        ComponentInstance outer = HostInstance.builder(store).addInstance("nested", inner).build();

        assertSame(inner, outer.getExport("nested"));
    }

    /** A core module the importer instantiates itself rather than calls. */
    @Test
    public void anInstanceMayExportACoreModule() {
        WasmModule module = WasmModule.builder().build();

        ComponentInstance host =
                HostInstance.builder(new ComponentStore()).addModule("m", module).build();

        assertSame(module, host.getExport("m"));
    }

    /** Exports are only readable once the instance is sealed, and sealing happens once. */
    @Test
    public void anInstanceCannotBeBuiltTwice() {
        var builder = HostInstance.builder(new ComponentStore());
        builder.build();

        assertThrows(IllegalStateException.class, builder::build);
    }

    /** Only instances sharing a store may be wired together. */
    @Test
    public void anInstanceBelongsToTheStoreItWasBuiltIn() {
        var store = new ComponentStore();

        ComponentInstance host = HostInstance.builder(store).build();

        assertSame(store, host.store());
        assertEquals(List.of(host), store.instances());
    }

    private static FuncType.Builder func() {
        return FuncType.builder();
    }

    private static ValType u32() {
        return ValType.builder().withPrimValType(PrimValType.U32).build();
    }

    private static ValType string() {
        return ValType.builder().withPrimValType(PrimValType.STRING).build();
    }

    private static LabelValType param(String label, ValType valType) {
        return LabelValType.builder().withLabel(label).withValType(valType).build();
    }
}
