package run.endive.cm.bindgen.it.worldexportkinds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import run.endive.cm.bindgen.it.Components;
import run.endive.cm.runtime.Bindgen;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;
import run.endive.runtime.TrapException;

/**
 * The world of wasmtime's all-world-export-kinds bindgen example, unchanged.
 *
 * <p>Every shape a world's exports take at once, which is a function of the world's own, an
 * interface written inline, and an interface exported by its id. The last two differ only in
 * whether the name carries a package qualification, so both become a wrapper class named after the
 * last segment.
 */
@Bindgen(world = "with-exports")
public class WorldExportKindsTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        component =
                Components.build(
                        Components.bytes("/with-exports.wat"),
                        Components.text("/wit/with-exports.wit"),
                        "with-exports");
    }

    /** A function the world exports in its own right. */
    @Test
    void aTopLevelExportIsCallable() {
        Log log = new Log();

        instantiate(log).run();

        assertEquals(List.of("ran"), log.messages);
    }

    /** An interface written inline in the world, reached through its accessor. */
    @Test
    void anInlineExportedInterfaceIsCallable() {
        Log log = new Log();
        WithExports bindings = instantiate(log);

        assertEquals("ok", bindings.environment().get("HOME"));
        assertEquals(List.of("HOME"), log.messages);
    }

    /** Two string arguments, both lowered into guest memory. */
    @Test
    void anInlineExportedInterfaceTakesSeveralArguments() {
        Log log = new Log();

        instantiate(log).environment().set("HOME", "/root");

        assertEquals(List.of("/root"), log.messages);
    }

    /**
     * An interface exported by its id rather than written inline. The guest traps unless it is
     * handed the values below, so this passing shows {@code u64} crossing intact.
     */
    @Test
    void anInterfaceExportedByIdIsCallable() {
        WithExports bindings = instantiate(new Log());

        assertEquals("kb", bindings.units().bytesToString(1024L));
        assertEquals("5s", bindings.units().durationToString(5L, 500L));
    }

    /** The trap above is live, so a value the guest does not expect is refused. */
    @Test
    void aValueTheGuestDoesNotExpectTraps() {
        WithExports bindings = instantiate(new Log());

        assertThrows(TrapException.class, () -> bindings.units().bytesToString(1L));
    }

    @Test
    void everyExportedInterfaceWrapperIsBuiltOnce() {
        WithExports bindings = instantiate(new Log());

        assertSame(bindings.environment(), bindings.environment());
        assertSame(bindings.units(), bindings.units());
    }

    private static WithExports instantiate(Log log) {
        return WithExports.instantiate(new ComponentStore(), component, log);
    }

    /** The world's one import, recording what the guest logs. */
    private static final class Log implements WithExports.Imports {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void log(String msg) {
            messages.add(msg);
        }
    }
}
