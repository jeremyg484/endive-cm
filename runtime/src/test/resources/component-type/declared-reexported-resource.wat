(component
  ;; Declares a component that imports a resource, re-exports it, and takes a
  ;; handle to it.
  (import "host" (instance $host
    (export "a-component" (component
      (import "t" (type $t (sub resource)))
      (export "u" (type (eq $t)))
      (export "f" (func (param "x" (own $t))))
    ))
  ))
)
