package run.endive.cm.runtime;

public final class Task {

    private int borrowCount;

    void borrow() {
        borrowCount++;
    }

    void unborrow() {
        borrowCount--;
    }
}
