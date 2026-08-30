package run.endive.cm.bindgen.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * A world importing both bare functions and an interface.
 *
 * <p>An imported interface is an instance rather than a function, so the generated bindings build
 * one with {@link run.endive.cm.runtime.HostInstance} and give the embedder a Java interface of its
 * own to implement. The guest hands the greeting it was given straight back through {@code log},
 * which is what shows the value survived the round trip rather than only that both were called.
 */
@Bindgen(world = "my-world")
public class WorldImportsTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        byte[] binary =
                ComponentNew.create(
                        ComponentEmbed.embed(resource("/my-world.wat"), text("/wit/my-world.wit")));

        ComponentValidate.validate(new ByteArrayInputStream(binary), "component-model");
        component =
                ComponentParser.builder()
                        .withValidation(false)
                        .build()
                        .parse(() -> new ByteArrayInputStream(binary));
    }

    @Test
    void everyImportIsReachedFromTheGuest() {
        Recorder recorder = new Recorder("hello");

        MyWorld.instantiate(new ComponentStore(), component, recorder).run();

        assertTrue(recorder.greeted, "expected the guest to call the imported greet");
        assertEquals(List.of("hello"), recorder.logged);
        assertEquals(1, recorder.ticks, "expected the guest to call tick on the interface");
    }

    /** The greeting travels into guest memory and back out, so what is logged is what was given. */
    @Test
    void aValueFromOneImportReachesAnother() {
        Recorder recorder = new Recorder("a longer greeting");

        MyWorld.instantiate(new ComponentStore(), component, recorder).run();

        assertEquals(List.of("a longer greeting"), recorder.logged);
    }

    @Test
    void theInterfaceIsAskedForOnlyOnce() {
        Recorder recorder = new Recorder("hello");

        MyWorld world = MyWorld.instantiate(new ComponentStore(), component, recorder);
        world.run();
        world.run();

        assertEquals(
                1, recorder.handedOut, "expected the interface to be resolved at instantiation");
        assertEquals(2, recorder.ticks);
    }

    @Test
    void nothingIsCalledBeforeTheGuestRuns() {
        Recorder recorder = new Recorder("hello");

        MyWorld.instantiate(new ComponentStore(), component, recorder);

        assertFalse(recorder.greeted);
        assertEquals(0, recorder.ticks);
    }

    /** One object implementing both the world's imports and the interface it imports. */
    private static final class Recorder implements MyWorld.Imports, MyWorld.MyCustomHost {

        private final String greeting;
        private final List<String> logged = new ArrayList<>();
        private boolean greeted;
        private int ticks;
        private int handedOut;

        Recorder(String greeting) {
            this.greeting = greeting;
        }

        @Override
        public String greet() {
            greeted = true;
            return greeting;
        }

        @Override
        public void log(String msg) {
            logged.add(msg);
        }

        @Override
        public MyWorld.MyCustomHost myCustomHost() {
            handedOut++;
            return this;
        }

        @Override
        public void tick() {
            ticks++;
        }
    }

    private static String text(String path) {
        return new String(resource(path), StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) {
        try (InputStream is = WorldImportsTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "resource not found: " + path);
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
