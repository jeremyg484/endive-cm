(component
  ;; A leaf component with one export and no imports.
  (component $Leaf
    (core module $m (func (export "f") (result i32) (i32.const 7)))
    (core instance $mi (instantiate $m))
    (func (export "f") (result u32) (canon lift (core func $mi "f")))
  )

  ;; A nested component may export a component; only the root may not.
  (component $Provider
    (import "leaf" (component $l (export "f" (func (result u32)))))
    (instance $i (export "c" (component $l)))
    (export "p" (instance $i))
  )
  (instance $prov (instantiate $Provider (with "leaf" (component $Leaf))))
  (alias export $prov "p" (instance $pi))

  ;; The consumer declares a NON-empty component type for that export.
  (component $Consumer
    (import "x" (instance
      (export "c" (component
        (export "f" (func (result u32)))
      ))
    ))
  )
  (instance (instantiate $Consumer (with "x" (instance $pi))))
)
