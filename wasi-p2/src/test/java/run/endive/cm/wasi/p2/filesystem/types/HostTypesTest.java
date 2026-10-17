package run.endive.cm.wasi.p2.filesystem.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.io.error.HostError;

class HostTypesTest {

    private final HostTypes types = new HostTypes();

    @Test
    void aStreamErrorFromTheFileSystemHasItsCode() {
        assertEquals(
                ErrorCode.NO_ENTRY,
                types.filesystemErrorCode(new HostError(new NoSuchFileException("a"))));
    }

    @Test
    void aStreamErrorFromElsewhereHasNoCode() {
        assertNull(types.filesystemErrorCode(new HostError(new IllegalStateException())));
    }
}
