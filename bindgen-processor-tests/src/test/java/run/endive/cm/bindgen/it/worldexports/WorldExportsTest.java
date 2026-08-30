package run.endive.cm.bindgen.it.worldexports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import run.endive.cm.parser.ComponentParser;
import run.endive.cm.runtime.Bindgen;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.tools.ComponentEmbed;
import run.endive.cm.tools.ComponentNew;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.types.WasmComponent;

/**
 * The world of wasmtime's world-exports bindgen example, unchanged.
 *
 * <p>An exported interface is an instance, so it becomes a wrapper class reached through an
 * accessor. The imported interface is named by its package rather than written inline, and one of
 * its functions takes a {@code list<u8>}, which arrives as a list of the Java type carrying
 * {@code u8}.
 */
@Bindgen(world = "hello-world", path = "wit/world-exports.wit")
public class WorldExportsTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        byte[] binary =
                ComponentNew.create(
                        ComponentEmbed.embed(
                                resource("/world-exports.wat"),
                                text("/wit/world-exports.wit"),
                                "hello-world"));

        ComponentValidate.validate(new ByteArrayInputStream(binary), "component-model");
        component =
                ComponentParser.builder()
                        .withValidation(false)
                        .build()
                        .parse(() -> new ByteArrayInputStream(binary));
    }

    /** Calling through the exported interface enters the guest, which calls back into the host. */
    @Test
    void anExportedInterfaceReachesTheImportedOne() {
        Host host = new Host();

        instantiate(host).demo().run();

        assertEquals(1, host.randomCalls);
        assertEquals(1, host.hashed.size());
    }

    /**
     * {@code list<u8>} arrives as a list of the type carrying {@code u8}, which is {@code Short}
     * rather than {@code Byte}, since a {@code u8} does not fit a signed byte.
     */
    @Test
    void aListArgumentArrivesElementByElement() {
        Host host = new Host();

        instantiate(host).demo().run();

        assertEquals(List.of((short) 10, (short) 20, (short) 255), host.hashed.get(0));
    }

    @Test
    void theExportedInterfaceWrapperIsBuiltOnce() {
        HelloWorld bindings = instantiate(new Host());

        assertSame(bindings.demo(), bindings.demo());
    }

    @Test
    void nothingIsCalledBeforeTheGuestRuns() {
        Host host = new Host();

        instantiate(host);

        assertEquals(0, host.randomCalls);
    }

    private static HelloWorld instantiate(HelloWorld.Host host) {
        return HelloWorld.instantiate(new ComponentStore(), component, () -> host);
    }

    /** The host side of {@code my:project/host}. */
    private static final class Host implements HelloWorld.Host {

        private final List<List<Short>> hashed = new ArrayList<>();
        private int randomCalls;

        @Override
        public Long genRandomInteger() {
            randomCalls++;
            return 7L;
        }

        @Override
        public String sha256(List<Short> bytes) {
            hashed.add(new ArrayList<>(bytes));
            return "digest";
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
