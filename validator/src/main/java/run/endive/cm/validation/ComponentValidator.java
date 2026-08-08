package run.endive.cm.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import run.endive.cm.parser.ComponentParser;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.tools.ComponentValidateException;
import run.endive.cm.types.AliasSection;
import run.endive.cm.types.CanonSection;
import run.endive.cm.types.Export;
import run.endive.cm.types.ExportSection;
import run.endive.cm.types.ExternDesc;
import run.endive.cm.types.Import;
import run.endive.cm.types.ImportSection;
import run.endive.cm.types.Section;
import run.endive.cm.types.Sort;
import run.endive.cm.types.WasmComponent;
import run.endive.cm.types.canon.CanonLift;

/**
 * Decides whether a component binary is one this project will accept.
 *
 * <p>Two things stand between a well-formed binary and a usable component. The first is
 * validity in the Component Model's own terms, which {@code wasm-tools validate} settles.
 * The second is a handful of restrictions that sit outside the spec: shapes the format permits
 * but that an embedder has no way to honour, and which every implementation therefore has to
 * rule out for itself. A root component cannot import a component, because nothing outside it
 * exists to supply one; it cannot export a component, because there is no one to receive it;
 * and it cannot re-export a function it merely imported, because that function has no
 * implementation to stand behind the export.
 *
 * <p>Both kinds of rejection surface as {@link ComponentValidationException}, so callers need
 * not care which noticed.
 */
public final class ComponentValidator {

    private ComponentValidator() {}

    /**
     * @throws ComponentValidationException if the component is invalid or uses a shape this
     *     project does not support
     */
    public static void validate(InputStream is) {
        byte[] bytes = readAll(is);
        try {
            ComponentValidate.validate(new ByteArrayInputStream(bytes));
        } catch (ComponentValidateException e) {
            throw new ComponentValidationException(e.getMessage(), e);
        }
        checkRestrictions(
                ComponentParser.builder().build().parse(() -> new ByteArrayInputStream(bytes)));
    }

    /**
     * Applies the restrictions that only bite at the root, where there is no enclosing
     * component to resolve against. Nested components are free to do all of this, because
     * their parent supplies what they ask for and consumes what they hand back.
     */
    private static void checkRestrictions(WasmComponent component) {
        // Whether the function at each index of the component's function index space came from
        // an import, tracked in the order the sections build that space up.
        List<Boolean> functionIsImported = new ArrayList<>();
        for (Section section : component.sections()) {
            if (section instanceof ImportSection) {
                for (Import imp : ((ImportSection) section).imports()) {
                    if (imp.externDesc().kind() == ExternDesc.Kind.COMPONENT) {
                        throw new ComponentValidationException(
                                "root-level component imports are not supported");
                    }
                    if (imp.externDesc().kind() == ExternDesc.Kind.FUNC) {
                        functionIsImported.add(true);
                    }
                }
            } else if (section instanceof CanonSection) {
                for (var canon : ((CanonSection) section).canons()) {
                    // Only `canon lift` produces a component-level function; `canon lower` and
                    // the resource built-ins produce core ones.
                    if (canon instanceof CanonLift) {
                        functionIsImported.add(false);
                    }
                }
            } else if (section instanceof AliasSection) {
                for (var alias : ((AliasSection) section).aliases()) {
                    if (alias.sort().kind() == Sort.Kind.FUNC) {
                        functionIsImported.add(false);
                    }
                }
            } else if (section instanceof ExportSection) {
                for (Export export : ((ExportSection) section).exports()) {
                    checkExport(export, functionIsImported);
                    if (export.sortIdx().sort().kind() == Sort.Kind.FUNC) {
                        functionIsImported.add(false);
                    }
                }
            }
        }
    }

    private static void checkExport(Export export, List<Boolean> functionIsImported) {
        Sort.Kind sort = export.sortIdx().sort().kind();
        if (sort == Sort.Kind.COMPONENT) {
            throw new ComponentValidationException(
                    "exporting a component from the root component is not supported");
        }
        if (sort != Sort.Kind.FUNC) {
            return;
        }
        int idx = (int) export.sortIdx().idx();
        if (idx >= 0 && idx < functionIsImported.size() && functionIsImported.get(idx)) {
            throw new ComponentValidationException(
                    "component export `"
                            + export.name()
                            + "` is a reexport of an imported function which is not implemented");
        }
    }

    private static byte[] readAll(InputStream is) {
        try {
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
