package run.endive.cm.abi;

public interface Callback {

    boolean invoke(int ctx, int event, int payload);
}
