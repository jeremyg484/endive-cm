package run.endive.cm.tools;

public class ComponentEmbedException extends RuntimeException {

    public ComponentEmbedException(String message) {
        super(message);
    }

    public ComponentEmbedException(String message, Throwable cause) {
        super(message, cause);
    }
}
