package run.endive.cm.wasi.p2.filesystem.preopens;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import run.endive.cm.runtime.Tuple2;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.preopens.Host;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Descriptor;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.filesystem.types.HostDirectory;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

/**
 * The host side of {@code wasi:filesystem/preopens}, granting a guest the directories it was
 * configured with.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/preopens.wit">wasi:filesystem/preopens</a>
 */
public class HostPreopens implements Host {

    private final List<Tuple2<Descriptor, String>> directories;

    /**
     * Each directory is granted with read and directory mutation access.
     *
     * @param directories each host directory, keyed by the name the guest sees it under
     * @param poll the poll file streams subscribe through
     */
    public HostPreopens(Map<String, Path> directories, HostPoll poll) {
        DescriptorFlags flags =
                DescriptorFlags.of(
                        DescriptorFlags.Flag.READ, DescriptorFlags.Flag.MUTATE_DIRECTORY);
        List<Tuple2<Descriptor, String>> granted = new ArrayList<>();
        for (Map.Entry<String, Path> directory : directories.entrySet()) {
            granted.add(
                    Tuple2.of(
                            new HostDirectory(directory.getValue(), flags, poll),
                            directory.getKey()));
        }
        this.directories = List.copyOf(granted);
    }

    @Override
    public List<Tuple2<Descriptor, String>> getDirectories() {
        return directories;
    }
}
