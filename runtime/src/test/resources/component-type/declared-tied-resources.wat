(component
  ;; An `eq` bound ties "s" to "r": the type says they are one resource, not two.
  (import "host" (instance $host
    (export "a-component" (component
      (export "r" (type (sub resource)))
      (export "s" (type (eq 0)))
    ))
  ))
)
