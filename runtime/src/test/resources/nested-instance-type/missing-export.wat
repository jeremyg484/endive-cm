;; The host's `nested` instance exports `return-four`, not `return-five`.
(component
  (import "host" (instance $host
    (export "nested" (instance
      (export "return-five" (func (result u32)))
    ))
  ))
)
