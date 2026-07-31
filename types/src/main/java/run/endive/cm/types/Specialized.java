package run.endive.cm.types;

public interface Specialized<T extends DefValType> {

    T despecialize();
}
