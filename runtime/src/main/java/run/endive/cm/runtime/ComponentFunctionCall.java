package run.endive.cm.runtime;

@FunctionalInterface
public interface ComponentFunctionCall {
    Object[] apply(Object... args);
}
