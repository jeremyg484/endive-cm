package run.endive.cm.bindgen;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Generation is checked by compiling an annotated source and comparing the result against a
 * checked-in expected source, the way Endive checks its own processors.
 *
 * <p>WIT is reached through the class path here, because the in-memory file manager these
 * compilations run under has no class output for Maven to have copied resources into.
 */
class BindgenProcessorTest {

    private static final List<File> WIT_ON_CLASSPATH =
            List.of(new File("src/test/resources"), new File("target/test-classes"));

    @Test
    void generatesHelloWorldBindings() {
        Compilation compilation = compile("HelloWorldHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.HelloWorld")
                .hasSourceEquivalentTo(JavaFileObjects.forResource("HelloWorldGenerated.java"));
    }

    /** A world importing an interface as well as bare functions. */
    @Test
    void generatesWorldImportBindings() {
        Compilation compilation = compile("WorldImportsHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.MyWorld")
                .hasSourceEquivalentTo(JavaFileObjects.forResource("MyWorldGenerated.java"));
    }

    /** The world of wasmtime's world-exports example, which also imports a named interface. */
    @Test
    void generatesWorldExportBindings() {
        Compilation compilation = compile("WorldExportsHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.HelloWorld")
                .hasSourceEquivalentTo(
                        JavaFileObjects.forResource("HelloWorldExportsGenerated.java"));
    }

    /** The world of wasmtime's imported-resources example, whose resource uses an enum. */
    @Test
    void generatesImportedResourceBindings() {
        Compilation compilation = compile("ImportedResourceHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.ImportSomeResources")
                .hasSourceEquivalentTo(
                        JavaFileObjects.forResource("ImportSomeResourcesGenerated.java"));
    }

    /** The world of wasmtime's all-world-export-kinds example. */
    @Test
    void generatesEveryKindOfWorldExport() {
        Compilation compilation = compile("WorldExportKindsHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.WithExports")
                .hasSourceEquivalentTo(JavaFileObjects.forResource("WithExportsGenerated.java"));
    }

    /** The world of wasmtime's exported-resources example, whose resource the guest implements. */
    @Test
    void generatesExportedResourceBindings() {
        Compilation compilation = compile("ExportedResourceHost.java");

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.ExportSomeResources")
                .hasSourceEquivalentTo(
                        JavaFileObjects.forResource("ExportSomeResourcesGenerated.java"));
    }

    @Test
    void inlineWitNeedsNoFile() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.InlineHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(inline = \"package my:project;\\n"
                                        + "world hello-world {\\n"
                                        + "  import name: func() -> string;\\n"
                                        + "  export greet: func();\\n"
                                        + "}\\n\")\n"
                                        + "public class InlineHost {}\n"));

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.HelloWorld")
                .hasSourceEquivalentTo(JavaFileObjects.forResource("HelloWorldGenerated.java"));
    }

    /**
     * WIT reserves fewer words than Java does, so a name like {@code new} is a WIT name but not a
     * Java one and has to be escaped. Generation used to fail on one with a parser crash.
     */
    @Test
    void witNamesJavaReservesAreEscaped() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.ReservedHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(inline = \"package k:w;\\n"
                                        + "world kw {\\n"
                                        + "  import new: func(class: string) -> string;\\n"
                                        + "  export final: func();\\n"
                                        + "}\\n\")\n"
                                        + "public class ReservedHost {}\n"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("endive.testing.Kw")
                .contentsAsUtf8String()
                .contains("String new_(String class_)");
    }

    @Test
    void aMissingWitFileIsReported() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.MissingHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(world = \"nowhere\")\n"
                                        + "public class MissingHost {}\n"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("wit/nowhere.wit");
    }

    @Test
    void anUnknownWorldIsReported() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.WrongWorldHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(world = \"other\", path ="
                                        + " \"wit/hello-world.wit\")\n"
                                        + "public class WrongWorldHost {}\n"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("world \"other\" was not found");
    }

    @Test
    void invalidWitIsReported() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.BadWitHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(inline = \"not valid wit {{{\")\n"
                                        + "public class BadWitHost {}\n"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("WIT could not be encoded");
    }

    @Test
    void givingBothInlineAndPathIsReported() {
        Compilation compilation =
                compile(
                        JavaFileObjects.forSourceString(
                                "endive.testing.BothHost",
                                "package endive.testing;\n"
                                        + "import run.endive.cm.runtime.Bindgen;\n"
                                        + "@Bindgen(inline = \"package a:b;\", path ="
                                        + " \"wit/hello-world.wit\")\n"
                                        + "public class BothHost {}\n"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("only one of inline and path");
    }

    private static Compilation compile(String resource) {
        return compile(JavaFileObjects.forResource(resource));
    }

    private static Compilation compile(javax.tools.JavaFileObject source) {
        return javac().withProcessors(new BindgenProcessor())
                .withClasspathFrom(BindgenProcessorTest.class.getClassLoader())
                .compile(source);
    }
}
