# Miscellaneous notes

Findings that shaped the implementation but do not belong in any one class.

## Validation is a precondition of linking

`ComponentParser.Builder#build` requires a `Validator` unless validation is explicitly
disabled, so a component reaching the linker has been validated. The linker relies on
that.

A validator sees one binary at a time. When a component instantiates a child it defines,
the validator has both the declaration and the definition in front of it, and it checks
instance and component subtyping there. It also tracks resource identity across that
boundary with its own identifier. Repeating those checks at link time decides nothing new,
so the linker skips them for nested instantiations and runs them only at the host boundary,
where `ComponentInstance#parent()` is null.

Skipping the nested case is sound by transitivity. A host value reaching a nested import
got there through a root import, so "host value conforms to the root declared type" is
checked by the linker and "root declared type conforms to the nested declaration" is
checked by the validator. The first link has to hold for the second to mean anything, which
is why the root checks must stay complete. Linking.md asks for the same thing from the
other direction when it says that holding a child inline or importing it is a bundling
choice that does not affect runtime behavior. Inline children are exactly the ones a
validator covers.

Measured over the spec suite, 114 of 210 guarded checks are skipped as nested and 96 run at
the root.

The checks that remain at the root, one per import kind, are function types, instance types,
core module types, type bounds including resource identity, and component types.

## Restrictions that come from the test suite rather than the model

`Validator` rejects three things the Component Model itself allows. Each is required by an
`assert_invalid` in the spec test suite, which asserts on the message text as well.

- Root level component imports, from `test/wasmtime/restrictions.wast` and
  `test/wasmtime/simple.wast`.
- Exporting a component from the root component, from `test/wasmtime/restrictions.wast`.
- Re-exporting an imported function, from `test/wasmtime/restrictions.wast`.

These live under `test/wasmtime`, so they are restrictions of one implementation that were
contributed to the suite. Linking.md builds its account of first order linking on a root
component importing its children, so the first of them may be relaxed later. The check in
`ComponentLinker#processComponentImport` exists for that day and is unreachable until then.

The export restriction only inspects the root export section, so a root component may still
export an instance that contains a component. That is the only way a component extern
inside a component type is reachable today.

## Comparing component types

Both sides of a component type comparison are uninstantiated, so a resource declaration has
no identity yet. Each side stands one in per declaration, and matching a `type` export by
name relates one side's stand-in to the other's. Handles then compare by identity the same
way they do everywhere else.

The relation has to be a function but does not have to be injective. Two abstract resource
declarations in a component type are two variables, not a promise that whatever satisfies
them differs, so one resource type may stand in for both. A validator accepts exactly that.
What does require two names to denote one type is an `eq` bound.

Declarations are walked in written order because a component type may only refer backwards.
That guarantees the `type` export introducing a resource is matched before any function
taking a handle to it is compared.

An export extends the index space of its sort. Reading an export before binding it is what
keeps later indices correct.

Four things are still refused rather than compared, all of them derivation gaps rather than
holes. An instance produced by `instantiate` needs the component index space followed
through instantiation. A type or instance reached by an alias needs the space the alias
reaches into. A handle naming a resource that no type export introduces has nothing to be
compared against. Core module externs are not derived at all.

## Places deliberately simpler than the specification

Re-entrance is refused per instance rather than by walking the instantiation tree. A
forthcoming change to the specification removes the guard completely.

## Deferred work

Automatic resolution of imports from components already instantiated into a `ComponentStore`
is deferred. It is first order linking in the sense of Linking.md, so it belongs above the
model rather than inside it, and it should stay explicit rather than scanning every instance
a store holds. `ComponentStore#register` is called for every instance at any nesting depth,
so a candidate set built from it would let a component's private inner instance satisfy an
unrelated import by name.

The async ABI is not implemented.
