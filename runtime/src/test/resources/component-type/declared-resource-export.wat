(component
  (import "host" (instance $host
    (export "a-component" (component
      (export "r" (type (sub resource)))
      (export "make" (func (result (own 0))))
    ))
  ))
)
