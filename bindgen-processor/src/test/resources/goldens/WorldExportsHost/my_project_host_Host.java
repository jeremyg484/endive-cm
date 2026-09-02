package endive.testing.my.project.host;

import java.util.List;
import javax.annotation.processing.Generated;

/**
 * The WIT interface {@code my:project/host}, which the embedder implements.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public interface Host {

    Long genRandomInteger();

    String sha256(List<Short> bytes);
}
