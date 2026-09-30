package run.endive.cm.bindgen.it.versionedimports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import run.endive.cm.bindgen.it.Components;
import run.endive.cm.bindgen.it.versionedimports.example.interfaceimports.logging.Host;
import run.endive.cm.bindgen.it.versionedimports.example.interfaceimports.logging.Level;
import run.endive.cm.runtime.Bindgen;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;

/** Runs a guest that calls a versioned interface through its generated Java host binding. */
@Bindgen(world = "versioned-with-imports", path = "wit/versioned-with-imports.wit")
public class VersionedInterfaceImportsTest {

    private static WasmComponent component;

    @BeforeAll
    static void buildComponent() {
        component =
                Components.build(
                        Components.bytes("/versioned-with-imports.wat"),
                        Components.text("/wit/versioned-with-imports.wit"),
                        "versioned-with-imports");
    }

    @Test
    void theGuestCallsTheVersionedImport() {
        Recorder recorder = new Recorder();

        VersionedWithImportsWorld.instantiate(new ComponentStore(), component, () -> recorder)
                .run();

        assertEquals(List.of(Level.WARN, Level.ERROR), recorder.levels);
        assertEquals(List.of("warn: starting", "error: done"), recorder.entries);
    }

    private static final class Recorder implements Host {

        private final List<Level> levels = new ArrayList<>();
        private final List<String> entries = new ArrayList<>();

        @Override
        public void log(Level level, String msg) {
            levels.add(level);
            entries.add(level.name().toLowerCase() + ": " + msg);
        }
    }
}
