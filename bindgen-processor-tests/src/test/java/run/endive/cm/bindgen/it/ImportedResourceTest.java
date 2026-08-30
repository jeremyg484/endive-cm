package run.endive.cm.bindgen.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * A world importing an interface that declares a resource the host implements.
 *
 * <p>A handle carries an integer across the ABI rather than an object, so the bindings keep a table
 * mapping one to the other. Dropping an owned handle in the guest runs the destructor, which is
 * what empties that table again.
 */
@Bindgen(world = "resource-world")
public class ImportedResourceTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        byte[] binary =
                ComponentNew.create(
                        ComponentEmbed.embed(
                                resource("/resource-world.wat"),
                                text("/wit/resource-world.wit"),
                                "resource-world"));

        ComponentValidate.validate(new ByteArrayInputStream(binary), "component-model");
        component =
                ComponentParser.builder()
                        .withValidation(false)
                        .build()
                        .parse(() -> new ByteArrayInputStream(binary));
    }

    /** The guest constructs a host resource and calls a method on it. */
    @Test
    void theGuestConstructsAndUsesAHostResource() {
        Types types = new Types();
        ResourceWorld bindings = instantiate(types);

        assertEquals(Long.valueOf(5L), bindings.openAndMeasure("hello"));
        assertEquals(List.of("hello"), types.opened);
    }

    /** Each construction is a distinct object, reached through its own handle. */
    @Test
    void everyConstructionIsItsOwnResource() {
        Types types = new Types();
        ResourceWorld bindings = instantiate(types);

        assertEquals(Long.valueOf(5L), bindings.openAndMeasure("first"));
        assertEquals(Long.valueOf(6L), bindings.openAndMeasure("second"));
        assertEquals(List.of("first", "second"), types.opened);
    }

    /** Dropping an owned handle runs the destructor, so the table does not grow without bound. */
    @Test
    void droppingAHandleReachesTheHost() {
        Types types = new Types();
        ResourceWorld bindings = instantiate(types);

        bindings.openAndMeasure("hello");
        bindings.openAndMeasure("again");

        assertEquals(2, types.dropped, "expected each dropped handle to reach the destructor");
    }

    /** A handle the guest keeps is not dropped, which is what tells the destructor apart. */
    @Test
    void aHandleTheGuestKeepsIsNotDropped() {
        Types types = new Types();
        ResourceWorld bindings = instantiate(types);

        bindings.openAndLeak("kept");

        assertEquals(1, types.opened.size());
        assertEquals(0, types.dropped, "expected no destructor for a handle still held");
    }

    private static ResourceWorld instantiate(Types types) {
        return ResourceWorld.instantiate(new ComponentStore(), component, () -> types);
    }

    /** The host side of the {@code types} interface, including its {@code file} resource. */
    private static final class Types implements ResourceWorld.Types {

        private final List<String> opened = new ArrayList<>();
        private int dropped;

        @Override
        public ResourceWorld.Types.File file(String name) {
            opened.add(name);
            return new File(name);
        }

        private final class File implements ResourceWorld.Types.File {

            private final String name;

            File(String name) {
                this.name = name;
            }

            @Override
            public Long nameLength() {
                return (long) name.length();
            }

            @Override
            public Boolean isOpen() {
                return Boolean.TRUE;
            }

            @Override
            public void drop() {
                dropped++;
            }
        }
    }

    private static String text(String path) {
        return new String(resource(path), StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) {
        try (InputStream is = ImportedResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "resource not found: " + path);
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
