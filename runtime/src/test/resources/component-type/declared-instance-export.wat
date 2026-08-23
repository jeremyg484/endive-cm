(component
  (import "host" (instance $host
    (export "a-component" (component
      (export "i" (instance (export "f" (func (result u32)))))
    ))
  ))
)
