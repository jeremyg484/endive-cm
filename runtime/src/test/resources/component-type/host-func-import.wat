(component
  ;; A root function import: the host hands over an implementation directly, so
  ;; nothing but the linker ever compares it against this declaration.
  (import "host-func" (func (param "x" u32) (result u32)))
)
