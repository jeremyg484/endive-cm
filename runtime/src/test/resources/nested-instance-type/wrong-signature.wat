;; `return-four` exists, but returns u32 rather than the u64 declared here.
(component
  (import "host" (instance $host
    (export "nested" (instance
      (export "return-four" (func (result u64)))
    ))
  ))
)
