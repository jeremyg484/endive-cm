(component
  ;; The host supplies "host"; its type declares a component export whose type
  ;; names one function. Everything about the check turns on what the host hands over.
  (import "host" (instance $host
    (export "a-component" (component
      (export "f" (func (result u32)))
    ))
  ))
)
