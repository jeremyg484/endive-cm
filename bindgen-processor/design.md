# Bindgen Processor High-Level Design

This module will provide WIT bindgen support for making it simple to build out host-side functionality that is targeting
a specific WIT world.

## Implementation Approach

We should take an approach that mimics the bindgen! macro that is used by Wasmtime, using an annotation processor as an
equivalent to the macro so that the necessary bindings may be generated as part of the Java compilation process.

This bindgen-processor skeleton Maven module has been created to house this, and an initial Bindgen annotation has been
added to the runtime module.

This functionality should initially just focus on WIT and the Component Model, but it should eventually become usable to
build WASI p2+ implementations. As this evolves, it may be necessary to further evolve the API of the runtime module to
make it simpler to provide host-side functions, etc.

The tests for bindgen-processor should mimic the strategy from Endive's HostModuleProcessor tests. We will also need to
establish a repeatable way to build .wasm component binaries that can be used in end-to-end tests for testing
interaction between the host functionality and wasm components that target the same WIT world. These full end-to-end
tests will likely need to reside in a separate module, such as a "bindgen-processor-tests" module.

The annotation processor should internally make use of the wasm-tools module (adding new APIs there as needed) to
convert WIT text into its binary format. The binary format uses the same structure as wasm components, so should be able
to be parsed with our current binary ComponentParser, using our WasmComponent AST as the basis for understanding the WIT
and generating the needed Java binding code from it.

The Bindgen annotation should be able to be used at the class level for simpler cases, or at the package level for more
complex bindings as would likely be needed in building WASI functionality.

We will start with the most basic use cases and build from there, using the examples from Wasmtime's bindgen! macro docs
as a guide. https://docs.wasmtime.dev/api/wasmtime/component/bindgen_examples/index.html

## Baseline

The pipeline this design assumes was spiked against the current code before any of it was written. What follows is
established fact rather than expectation.

### WIT text converts to binary through the wasm-tools module already present

The embedded wasm-tools guest accepts `component wit --wasm -o <out> <in>`, driven through the same ZeroFs and
WasiPreview1 plumbing `WitParser` already uses. Its output is byte-identical to the output of a native wasm-tools
1.245.1 on the same input. So this is a new entry point on the existing wasm-tools wrapper rather than new toolchain
work.

### ComponentParser reads the WIT binary unchanged

A WIT package encodes as a component whose exports name the package's top-level items.

- An `interface` becomes a `ComponentType` whose single export declaration names an `InstanceType` under the fully
  qualified interface id, such as `example:calc/types`. That instance type holds the interface's type definitions,
  each defined and then exported under its WIT name through an `eq` bound.
- A `world` becomes a `ComponentType` whose single export declaration names a nested `ComponentType` under the fully
  qualified world id, such as `example:calc/calculator`. That nested component type holds the world's `ImportDecl`s and
  `ExportDecl`s. A `use` in the world shows up as an imported instance followed by an alias and a type import for each
  used name.

Records, enums, variants, flags, function types, and their parameter names all survive the round trip into the
type model.

### Component binaries for end-to-end tests are buildable from .wat

`wasm-tools component embed --world <w> <wit> <core.wasm>` followed by `wasm-tools component new` produces a component
binary from a hand-written core module. This is the repeatable fixture strategy for the end-to-end tests, and it keeps
the project free of a guest language toolchain.

One non-obvious detail costs time if it is not known. A core module satisfies a world's top-level import by importing it
from the core module name `$root`, not from the interface or world id. Imports that come through a WIT interface use the
interface id as the core module name.

Neither `ComponentEmbed` nor `ComponentNew` exists in the wasm-tools module yet. Both are Phase 2 deliverables and both
are prerequisites for the end-to-end module.

### The runtime cannot currently be driven from outside its own package

`SpecTestImports` is the closest thing to what generated code has to emit, and almost everything it touches is
package-private. `ComponentInstance.Builder.instance()`, `addExport`, `addImport` and `declareHostResourceType` are all
package-private. `ComponentFunctionInstance.builder()` is public but wants a `ComponentInstance` and a `TypeResolver`,
and a host function whose type names anything beyond a primitive needs a type space for its `typeIdx` references to
resolve against. Closing this gap is the first piece of work, because nothing else can be generated until it is closed.

### Existing Java bindings for component types

`PrimitiveHostTypeDescriptor` and its siblings already fix the conventions, unsigned widening included. Bindgen inherits
them rather than inventing its own.

| WIT | Java |
|---|---|
| `bool` | `Boolean` |
| `s8` | `Byte` |
| `u8`, `s16` | `Short` |
| `u16`, `s32` | `Integer` |
| `u32`, `s64`, `u64`, `error-context` | `Long` |
| `u64` | `BigInteger`, alongside `Long` |
| `f32` | `Float` |
| `f64` | `Double` |
| `char` | `CharValue` |
| `string` | `String` |
| `list<T>` | `List` |
| `record`, `tuple`, `flags` | `Map` |
| `variant`, `enum`, `option`, `result` | `VariantValue` |
| `enum` | a Java enum, matched label by label after kebab-casing |
| `own<R>`, `borrow<R>` | `ResourceValue` |

## Design Decisions

### The Bindgen annotation

WIT is found the way the bindgen! macro finds it. A `wit` root directory holds the WIT, which in a conventional Maven
project means `src/main/resources/wit/`.

```java
@Bindgen(world = "calculator")                            // reads wit/
@Bindgen(path = "wit/calc", world = "calculator")         // an explicit root
@Bindgen(inline = "package ex:calc;\nworld calculator { ... }")
```

- `path` defaults to `wit` and is resolved as a resource path. Naming a directory takes the whole directory as the WIT
  package, naming a single file takes just that file.
- `world` names the world to generate for and may be omitted when the package declares exactly one.
- `inline` carries WIT text directly and is mutually exclusive with `path`.

Resources are looked up through the `Filer`, trying `CLASS_OUTPUT` and then `CLASS_PATH`. Maven's `process-resources`
phase populates `target/classes` before `compile`, so `CLASS_OUTPUT` is what resolves during a normal build.
`CLASS_PATH` covers two other cases, WIT arriving inside a dependency jar, which is how WASI p2 will consume the WASI
WIT, and the processor's own tests, where `compile-testing` runs an in-memory file manager that has no class output but
does honor a directory passed to `withClasspath`. Spike confirmed both behaviors.

The annotation moves to `SOURCE` retention, since nothing reads it at runtime, and its target widens to `{TYPE,
PACKAGE}` so that a `package-info.java` can carry the more elaborate bindings WASI will need.

`Bindgen` stays in the runtime module, which generated code depends on anyway. This module's dependency on `runtime`
therefore has to move from test scope to compile scope. If the processor's footprint ever becomes a concern, the
annotation can be split into its own artifact the way Endive separates `annotations` from `annotations/processor`.

A WIT package spread across a directory, with `deps/` and multiple files, needs the wasm-tools wrapper to mirror a tree
into ZeroFs rather than write the single `input.wit` it writes today.

### A public host-linking facade in the runtime

Rather than widen the visibility of `ComponentInstance.Builder` and expose index-space mechanics as supported API, the
runtime module gains a public facade for building a host-provided instance.

```java
var host = HostInstance.builder(store)
        .withType(pointRecord)                              // yields a ValType naming it
        .addFunction("host-log", logFuncType, args -> ...)
        .addResource("conn", destructor)
        .build();

linker.instantiate(store, component, Map.of("host", host));
```

It has to cover everything `SpecTestImports` does, which is declaring value types into the instance type space and
handing back `ValType`s that name them, declaring host resource types with a Java destructor, adding functions from a
`FuncType` and a `ComponentFunctionCall`, and exporting functions, resource types, nested instances, and core modules.

Rewriting `SpecTestImports` onto the facade is the proof that it is sufficient, and the sync ABI spec suite staying
green is the proof that it is correct. That rewrite should land before any code generation does.

### Generated types are nominal, and cross the boundary through descriptors

Bindgen generates a Java class per WIT record, a Java enum per WIT enum, a tagged-union class per variant, an
`EnumSet`-backed wrapper per flags, and an `AutoCloseable` class per resource.

The `HostTypeDescriptor` family is the mechanism for binding those to component types, so that mismatches are caught
ahead of any call rather than during one. Generated code that invokes a component goes through
`ComponentFunction.typed(resultDescriptor, paramDescriptors...)`, which checks the descriptors against the component
type when the function is cast and checks each argument's class when it is called.

`EnumHostTypeDescriptor` already binds a Java enum to a WIT `enum` or `flags` and already does the kebab-case label
matching, so generated enums use it directly. The rest of the family names ABI representations rather than user classes,
`RecordHostTypeDescriptor` binding `Map` and `VariantHostTypeDescriptor` binding `VariantValue`, so a generated `Point`
is not something they can name. `HostTypeDescriptor` therefore grows so that a descriptor can both match a generated
class and carry the mapping to and from the ABI representation that class stands for.

The canonical-abi module is not touched. Lifting and lowering keep working on the representations they already handle,
which is what keeps the passing sync ABI spec tests untouched. The cost is one allocation per aggregate per call, which
is the right trade for now and can be revisited once there is something to measure.

### Type metadata is generated as builder code

Generated code reconstructs the `ValType` and `FuncType` graph with the existing builders, in one package-private static
holder per world so that nothing is duplicated across the generated classes, with structurally identical types shared.

```java
final class Calculator_Types {
    static final ValType U32 = ValType.builder().withPrimValType(PrimValType.U32).build();
    static final FuncType HOST_LOG =
            FuncType.builder()
                    .addParam(LabelValType.builder().withLabel("msg").withValType(STRING).build())
                    .build();
}
```

Everything stays visible to the golden-file tests and nothing is read from the classpath at runtime. The holder also
carries the generated descriptor constants. This is verbose for something the size of WASI, which is a code-size concern
to revisit rather than a correctness one.

### Naming

| WIT | Java |
|---|---|
| world `calculator` | class `Calculator`, imports interface `Calculator.Imports` |
| interface `example:calc/types` | nested class `Calculator.Types` |
| function `host-log` | method `hostLog` |
| type `point` | class `Point` |
| record field `first-name` | field and accessor `firstName` |
| enum case `red` | constant `RED` |
| variant case `not-found` | case class `NotFound` |

### Generated shape

Mirroring bindgen!, a world produces one class carrying an interface the embedder implements for the world's imports and
typed wrappers for its exports.

```java
public final class Calculator {

    public interface Imports {
        void hostLog(String msg);
    }

    public static Calculator instantiate(ComponentStore store, WasmComponent component, Imports imports);

    public long add(long a, long b);

    public double area(Point p);
}
```

## Module Layout

- `bindgen-processor` holds the processor and its golden-file tests, following `HostModuleProcessorTest`. Sources are
  built with JavaParser, written through the `Filer`, and compared against checked-in expected sources with
  `compile-testing`. WIT for those tests lives in `src/test/resources/wit/` and reaches the processor through
  `withClasspath`.
- `bindgen-processor-tests` holds the end-to-end tests, where a `.wat` fixture and a `.wit` file are turned into a
  component, the generated bindings are compiled against it, and host and guest call each other. This module is blocked
  on `ComponentEmbed` and `ComponentNew`.

## Staging

Following the order of the bindgen! examples.

0. A world with one imported function and one exported function over primitives and strings.
1. World imports, both top-level functions and an inline interface.
2. World exports.
3. Imported interfaces.
4. Imported resources.
5. All kinds of world export.
6. Exported resources.

The async example is out of scope. Async is unimplemented across the whole project and the runtime rejects it.

## Prerequisites

1. `HostInstance`, the public host-linking facade, with `SpecTestImports` rewritten onto it and the spec suite green.
2. WIT-to-binary encoding on the wasm-tools module.
3. `ComponentEmbed` and `ComponentNew` on the wasm-tools module, before the end-to-end module.
