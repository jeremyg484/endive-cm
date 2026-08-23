(component
  (component $Provider
    ;; Defined here, exported directly — its type is inferred from the definition,
    ;; never stated in an import declaration.
    (component $Leaf
      (core module $m (func (export "f") (result i32) (i32.const 7)))
      (core instance $mi (instantiate $m))
      (func (export "f") (result u32) (canon lift (core func $mi "f")))
    )
    (instance $i (export "c" (component $Leaf)))
    (export "p" (instance $i))
  )
  (instance $prov (instantiate $Provider))
  (alias export $prov "p" (instance $pi))

  (component $Consumer
    (import "x" (instance
      (export "c" (component (export "f" (func (result u32)))))
    ))
  )
  (instance (instantiate $Consumer (with "x" (instance $pi))))
)
