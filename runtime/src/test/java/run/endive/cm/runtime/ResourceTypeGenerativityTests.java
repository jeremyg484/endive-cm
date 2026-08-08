package run.endive.cm.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import run.endive.cm.parser.ComponentParser;

/**
 * Resource types are <em>generative</em>: declaring one inside a component means every
 * instantiation of that component brings a distinct type into existence, however identical the
 * declarations look. Handles minted against one must not pass the guards of another.
 *
 * <p>The spec suite states this ({@code wasmtime/instance.wast}, "all instances have different
 * resource types") but cannot pin it down here: its harness builds a fresh linker per command,
 * so two top-level instantiations land in unrelated stores and would come out distinct however
 * identity were assigned. The case that actually tests the rule is two instantiations of the
 * same subcomponent <em>within one root</em>, which no {@code .wast} reaches — hence this.
 */
public class ResourceTypeGenerativityTests {

    /**
     * {@code wasmtime/resources.wast} line 483: a component that instantiates one inner
     * component — which declares a resource — twice over.
     */
    private static final String TWO_INSTANTIATIONS = "/wasmtime-resources/input.16.wasm";

    @Test
    public void instantiatingOneComponentTwiceCreatesTwoResourceTypes() {
        ComponentStore root = instantiate(TWO_INSTANTIATIONS).store();

        ResourceTypeInstance first = declaredResourceType(root.getChildInstance(0));
        ResourceTypeInstance second = declaredResourceType(root.getChildInstance(1));

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(
                first,
                second,
                "each instantiation must declare its own resource type, so that a handle from"
                        + " one is not usable against the other");
    }

    @Test
    public void eachInstantiationImplementsItsOwnResourceType() {
        ComponentStore root = instantiate(TWO_INSTANTIATIONS).store();

        ComponentInstance first = root.getChildInstance(0);
        ComponentInstance second = root.getChildInstance(1);

        // Which instance implements a resource type decides where `lower_borrow` hands over a
        // representation rather than a handle, so the two must not answer for each other.
        assertSame(first, declaredResourceType(first).impl());
        assertSame(second, declaredResourceType(second).impl());
    }

    /** The resource type at index 0 of an instance's own type index space. */
    private static ResourceTypeInstance declaredResourceType(ComponentInstance instance) {
        return instance.store().resourceTypeAt(0);
    }

    private static ComponentInstance instantiate(String resourcePath) {
        byte[] bytes = loadBytes(resourcePath);
        var component =
                ComponentParser.builder().build().parse(() -> new ByteArrayInputStream(bytes));
        return ComponentLinker.builder().build().instantiate(component);
    }

    private static byte[] loadBytes(String resourcePath) {
        try (InputStream is =
                ResourceTypeGenerativityTests.class.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Resource not found: " + resourcePath);
            return is.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
