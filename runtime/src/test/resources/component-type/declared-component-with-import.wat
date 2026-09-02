(component
  ;; The declared type promises the component one import, so a component that
  ;; asks for it links, and one that asks for something else does not.
  (import "host" (instance $host
    (export "a-component" (component
      (import "dep" (func (result u32)))
      (export "f" (func (result u32)))
    ))
  ))
)
