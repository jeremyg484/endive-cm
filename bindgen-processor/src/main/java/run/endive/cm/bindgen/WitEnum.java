package run.endive.cm.bindgen;

import java.util.List;
import java.util.Objects;

/** An enum an interface declares, under the name its export gives it. */
final class WitEnum {

    private final String name;
    private final List<String> labels;

    WitEnum(String name, List<String> labels) {
        this.name = Objects.requireNonNull(name, "name");
        this.labels = List.copyOf(labels);
    }

    String name() {
        return name;
    }

    List<String> labels() {
        return labels;
    }
}
