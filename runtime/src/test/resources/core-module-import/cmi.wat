(component
  ;; The embedder supplies this module; nothing that validated this component ever saw it,
  ;; so the declared type is the only statement of what it has to be.
  (import "m" (core module $m
    (export "f" (func (result i32)))
  ))
)
