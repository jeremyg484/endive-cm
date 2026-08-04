package run.endive.cm.abi;

@FunctionalInterface
public interface PostReturn {
    void call(long... args);
}
