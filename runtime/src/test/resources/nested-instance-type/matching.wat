;; The host's `nested` instance really does export `return-four : () -> u32`.
(component
  (import "host" (instance $host
    (export "nested" (instance
      (export "return-four" (func (result u32)))
    ))
  ))
)
