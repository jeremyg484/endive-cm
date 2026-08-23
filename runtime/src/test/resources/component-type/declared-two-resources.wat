(component
  ;; Two separate resource declarations are two type variables, not a promise that
  ;; whatever satisfies them differs.
  (import "host" (instance $host
    (export "a-component" (component
      (export "r" (type (sub resource)))
      (export "s" (type (sub resource)))
    ))
  ))
)
