package run.endive.cm.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Building a component from a core module and a world, which is how a test fixture is made without
 * a guest language toolchain in the way.
 */
public class ComponentBuildTest {

    /** Magic, then version {@code 0x000d} and layer {@code 0x0001}, which marks a component. */
    private static final byte[] COMPONENT_PREAMBLE = {
        0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00
    };

    /** Magic, then version {@code 0x0001} and layer {@code 0x0000}, which marks a core module. */
    private static final byte[] MODULE_PREAMBLE = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};

    private static final String WIT =
            "package my:project;\n"
                    + "world hello-world {\n"
                    + "  import name: func() -> string;\n"
                    + "  export greet: func();\n"
                    + "}\n";

    /**
     * A world's top-level import binds to the core module name {@code $root}, rather than to the
     * world's own id, which is what a core module has to import it from.
     */
    private static final String WAT =
            "(module\n"
                    + "  (import \"$root\" \"name\" (func $name (param i32)))\n"
                    + "  (memory (export \"memory\") 1)\n"
                    + "  (global $heap (mut i32) (i32.const 1024))\n"
                    + "  (func (export \"cabi_realloc\") (param i32 i32 i32 i32) (result i32)\n"
                    + "    (local $p i32)\n"
                    + "    global.get $heap\n"
                    + "    local.set $p\n"
                    + "    global.get $heap\n"
                    + "    local.get 3\n"
                    + "    i32.add\n"
                    + "    global.set $heap\n"
                    + "    local.get $p)\n"
                    + "  (func (export \"greet\")\n"
                    + "    i32.const 0\n"
                    + "    call $name))\n";

    @Test
    void embedProducesACoreModuleCarryingTheWorld() {
        byte[] embedded = ComponentEmbed.embed(wat(), WIT);

        assertArrayEquals(MODULE_PREAMBLE, Arrays.copyOf(embedded, MODULE_PREAMBLE.length));
        assertTrue(
                contains(embedded, "component-type"),
                "expected a component-type custom section, which is what embedding adds");
    }

    @Test
    void embedAcceptsANamedWorld() {
        assertArrayEquals(
                MODULE_PREAMBLE,
                Arrays.copyOf(
                        ComponentEmbed.embed(wat(), WIT, "hello-world"), MODULE_PREAMBLE.length));
    }

    @Test
    void embedRejectsAWorldThePackageDoesNotDeclare() {
        assertThrows(
                ComponentEmbedException.class, () -> ComponentEmbed.embed(wat(), WIT, "nowhere"));
    }

    /** A module whose imports do not match the world cannot carry it. */
    @Test
    void embedRejectsAModuleThatDoesNotMatchTheWorld() {
        byte[] mismatched = "(module (func (export \"other\")))".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                RuntimeException.class,
                () -> ComponentNew.create(ComponentEmbed.embed(mismatched, WIT)));
    }

    @Test
    void newProducesAComponent() {
        byte[] component = ComponentNew.create(ComponentEmbed.embed(wat(), WIT));

        assertArrayEquals(COMPONENT_PREAMBLE, Arrays.copyOf(component, COMPONENT_PREAMBLE.length));
    }

    /** The component declares the world it was built from, which is what the runtime links against. */
    @Test
    void theBuiltComponentCarriesTheWorld() {
        byte[] component = ComponentNew.create(ComponentEmbed.embed(wat(), WIT));

        String wit = WitParser.parse(new ByteArrayInputStream(component));

        assertTrue(wit.contains("import name: func() -> string"), wit);
        assertTrue(wit.contains("export greet: func()"), wit);
    }

    @Test
    void newRejectsAModuleWithNoEmbeddedTypes() {
        assertThrows(ComponentNewException.class, () -> ComponentNew.create(wat()));
    }

    @Test
    void theBuiltComponentValidates() {
        byte[] component = ComponentNew.create(ComponentEmbed.embed(wat(), WIT));

        ComponentValidate.validate(new ByteArrayInputStream(component), "component-model");
        assertFalse(component.length == 0);
    }

    private static byte[] wat() {
        return WAT.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code needle} appears in {@code haystack}, for finding a section name. */
    private static boolean contains(byte[] haystack, String needle) {
        byte[] bytes = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - bytes.length; i++) {
            for (int j = 0; j < bytes.length; j++) {
                if (haystack[i + j] != bytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
