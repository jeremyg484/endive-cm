package run.endive.cm.runtime;

import java.io.InputStream;
import run.endive.cm.parser.ComponentValidationException;
import run.endive.cm.parser.Validator;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.tools.ComponentValidateException;

public final class SpecTestValidator implements Validator {

    public static final SpecTestValidator INSTANCE = new SpecTestValidator();

    private SpecTestValidator() {}

    @Override
    public void validateBinary(InputStream wasm) throws ComponentValidationException {
        try {
            ComponentValidate.validate(wasm, "cm-implements");
        } catch (ComponentValidateException e) {
            throw new ComponentValidationException(e.getMessage(), e);
        }
    }
}
