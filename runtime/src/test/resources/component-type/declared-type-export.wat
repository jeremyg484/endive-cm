(component
  (import "host" (instance $host
    (export "a-component" (component
      (type $T (record (field "a" u32)))
      (export "t" (type (eq $T)))
      (export "f" (func (result u32)))
    ))
  ))
)
