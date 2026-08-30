package run.endive.cm.bindgen.it;

import java.io.InputStream;
import run.endive.cm.parser.ComponentValidationException;
import run.endive.cm.parser.Validator;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.tools.ComponentValidateException;

/**
 * Validates a component the way an embedder is expected to, with {@code wasm-tools validate} wired
 * in behind {@link Validator}.
 *
 * <p>The components these tests build are assembled from hand-written core modules, so validating
 * them is what keeps the tests honest about exercising the linker against something a real
 * toolchain could have produced.
 */
public final class TestValidator implements Validator {

    public static final TestValidator INSTANCE = new TestValidator();

    private TestValidator() {}

    @Override
    public void validateBinary(InputStream wasm) throws ComponentValidationException {
        try {
            ComponentValidate.validate(wasm, "cm-implements");
        } catch (ComponentValidateException e) {
            throw new ComponentValidationException(e.getMessage(), e);
        }
    }
}
