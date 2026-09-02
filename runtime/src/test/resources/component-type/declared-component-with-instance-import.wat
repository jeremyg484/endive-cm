(component
  ;; Declares a component that is promised an instance exporting "a" and "b".
  (import "host" (instance $host
    (export "a-component" (component
      (import "dep" (instance
        (export "a" (func (result u32)))
        (export "b" (func (result u32)))
      ))
      (export "f" (func (result u32)))
    ))
  ))
)
