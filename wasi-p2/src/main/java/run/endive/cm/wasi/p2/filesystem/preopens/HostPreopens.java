package run.endive.cm.wasi.p2.filesystem.preopens;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import run.endive.cm.runtime.Tuple2;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.preopens.Host;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Descriptor;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.filesystem.types.HostDirectory;
import run.endive.cm.wasi.p2.filesystem.types.OpenFiles;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

/**
 * The host side of {@code wasi:filesystem/preopens}, granting a guest its configured directories.
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
        this(directories, poll, new OpenFiles());
    }

    /**
     * Each directory is granted with read and directory mutation access.
     *
     * @param directories each host directory, keyed by the name the guest sees it under
     * @param poll the poll file streams subscribe through
     * @param files where the files opened through the directories are tracked
     */
    public HostPreopens(Map<String, Path> directories, HostPoll poll, OpenFiles files) {
        this(directories, Set.of(), poll, files);
    }

    /**
     * Each directory is granted with read access, and with directory mutation access unless it is
     * read-only.
     *
     * @param directories each host directory, keyed by the name the guest sees it under
     * @param readOnly the names of the directories the guest may only read
     * @param poll the poll file streams subscribe through
     * @param files where the files opened through the directories are tracked
     */
    public HostPreopens(
            Map<String, Path> directories, Set<String> readOnly, HostPoll poll, OpenFiles files) {
        DescriptorFlags mutable =
                DescriptorFlags.of(
                        DescriptorFlags.Flag.READ, DescriptorFlags.Flag.MUTATE_DIRECTORY);
        DescriptorFlags readable = DescriptorFlags.of(DescriptorFlags.Flag.READ);
        List<Tuple2<Descriptor, String>> granted = new ArrayList<>();
        for (Map.Entry<String, Path> directory : directories.entrySet()) {
            DescriptorFlags flags = readOnly.contains(directory.getKey()) ? readable : mutable;
            granted.add(
                    Tuple2.of(
                            new HostDirectory(directory.getValue(), flags, poll, files),
                            directory.getKey()));
        }
        this.directories = List.copyOf(granted);
    }

    @Override
    public List<Tuple2<Descriptor, String>> getDirectories() {
        return directories;
    }
}
