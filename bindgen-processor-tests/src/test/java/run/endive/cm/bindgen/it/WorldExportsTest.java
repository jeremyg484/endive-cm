package run.endive.cm.bindgen.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import run.endive.cm.parser.ComponentParser;
import run.endive.cm.runtime.Bindgen;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.tools.ComponentEmbed;
import run.endive.cm.tools.ComponentNew;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.types.WasmComponent;
import run.endive.wasm.WasmEngineException;

/**
 * A world exporting functions of its own and an interface, alongside an imported interface named by
 * its package.
 *
 * <p>An exported interface is an instance, so it becomes a wrapper class reached through an
 * accessor, mirroring the way the imports side gives the embedder an interface to implement.
 */
@Bindgen(world = "exports-world", path = "wit/exports-world.wit")
public class WorldExportsTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        byte[] binary =
                ComponentNew.create(
                        ComponentEmbed.embed(
                                resource("/exports-world.wat"),
                                text("/wit/exports-world.wit"),
                                "exports-world"));

        ComponentValidate.validate(new ByteArrayInputStream(binary), "component-model");
        component =
                ComponentParser.builder()
                        .withValidation(false)
                        .build()
                        .parse(() -> new ByteArrayInputStream(binary));
    }

    /** {@code u32} does not fit an int, so the bindings carry it as a {@link Long}. */
    @Test
    void anExportedFunctionTakesAndReturnsValues() {
        ExportsWorld bindings = instantiate(new Host(7L));

        assertEquals(Long.valueOf(30L), bindings.add(10L, 20L));
    }

    /** A string argument is lowered into guest memory, and the guest reports what it received. */
    @Test
    void aStringArgumentReachesTheGuest() {
        ExportsWorld bindings = instantiate(new Host(7L));

        assertEquals(Long.valueOf(5L), bindings.countBytes("hello"));
        assertEquals(Long.valueOf(0L), bindings.countBytes(""));
        assertEquals(Long.valueOf(9L), bindings.countBytes("wide éé"));
    }

    /** The exported interface is reached through its accessor, and calling it enters the guest. */
    @Test
    void anExportedInterfaceIsCallable() {
        Host host = new Host(7L);
        ExportsWorld bindings = instantiate(host);

        bindings.demo().run();

        assertEquals(1, host.calls, "expected run to call the imported interface");
    }

    @Test
    void theExportedInterfaceWrapperIsBuiltOnce() {
        ExportsWorld bindings = instantiate(new Host(7L));

        assertSame(bindings.demo(), bindings.demo());
    }

    /** Narrowing checks each argument's class, so the wrong one is refused before the call. */
    @Test
    void anArgumentOfTheWrongClassIsRefused() {
        ExportsWorld bindings = instantiate(new Host(7L));

        assertThrows(WasmEngineException.class, () -> bindings.countBytes(null));
    }

    private static ExportsWorld instantiate(ExportsWorld.Host host) {
        return ExportsWorld.instantiate(new ComponentStore(), component, () -> host);
    }

    /** Counts what the guest asks for, so a call through the exported interface is observable. */
    private static final class Host implements ExportsWorld.Host {

        private final Long value;
        private int calls;

        Host(Long value) {
            this.value = value;
        }

        @Override
        public Long genRandomInteger() {
            calls++;
            return value;
        }
    }

    private static String text(String path) {
        return new String(resource(path), StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) {
        try (InputStream is = WorldExportsTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "resource not found: " + path);
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
