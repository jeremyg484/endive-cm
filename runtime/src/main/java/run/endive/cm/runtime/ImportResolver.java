package run.endive.cm.runtime;

@FunctionalInterface
public interface ImportResolver {

    Object resolve(String name);
}
