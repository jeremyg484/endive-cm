package run.endive.cm.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class WitParserTest {

    /** Magic, then version {@code 0x000d} and layer {@code 0x0001}, which marks a component. */
    private static final byte[] COMPONENT_PREAMBLE = {
        0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00
    };

    private static final String HELLO_WORLD =
            "package my:project;\n"
                    + "world hello-world {\n"
                    + "  import name: func() -> string;\n"
                    + "  export greet: func();\n"
                    + "}\n";

    @Test
    void parseExampleWit() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/example.wit")) {
            String result = WitParser.parse(is);
            assertNotNull(result);
            assertTrue(result.contains("greet"));
            assertTrue(result.contains("add"));
            assertTrue(result.contains("host-log"));
        }
    }

    @Test
    void parseWitString() {
        String wit =
                "package test:simple;\n"
                        + "world simple {\n"
                        + "  export hello: func() -> string;\n"
                        + "}\n";
        String result = WitParser.parse(wit);
        assertNotNull(result);
        assertTrue(result.contains("hello"));
    }

    @Test
    void parseInvalidWitThrows() {
        assertThrows(WitParseException.class, () -> WitParser.parse("not valid wit {{{"));
    }

    /** A WIT package encodes as a component, which is what makes it readable by the parser. */
    @Test
    void encodeProducesAComponent() {
        byte[] encoded = WitParser.encode(HELLO_WORLD);

        assertTrue(encoded.length > COMPONENT_PREAMBLE.length, "expected more than a preamble");
        assertArrayEquals(COMPONENT_PREAMBLE, Arrays.copyOf(encoded, COMPONENT_PREAMBLE.length));
    }

    /**
     * Reading the encoded package back gives the WIT it came from. wasm-tools tells text and binary
     * apart by content, so the same command reads either.
     */
    @Test
    void encodedWitReadsBackAsTheSamePackage() {
        byte[] encoded = WitParser.encode(HELLO_WORLD);

        String round = WitParser.parse(new ByteArrayInputStream(encoded));

        assertTrue(round.contains("package my:project"), round);
        assertTrue(round.contains("world hello-world"), round);
        assertTrue(round.contains("import name: func() -> string"), round);
        assertTrue(round.contains("export greet: func()"), round);
    }

    @Test
    void encodeExampleWit() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/example.wit")) {
            byte[] encoded = WitParser.encode(is);

            String round = WitParser.parse(new ByteArrayInputStream(encoded));
            assertTrue(round.contains("greet"), round);
            assertTrue(round.contains("add"), round);
            assertTrue(round.contains("host-log"), round);
        }
    }

    @Test
    void encodeInvalidWitThrows() {
        assertThrows(WitParseException.class, () -> WitParser.encode("not valid wit {{{"));
    }
}
