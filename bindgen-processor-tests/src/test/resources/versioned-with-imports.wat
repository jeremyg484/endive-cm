;; The versioned form of with-imports.wat. The guest calls the host twice.
(module
  (import "example:interface-imports/logging@0.2.0" "log" (func $log (param i32 i32 i32)))
  (memory (export "memory") 1)
  (data (i32.const 100) "starting")
  (data (i32.const 120) "done")

  (func (export "run")
    i32.const 2
    i32.const 100
    i32.const 8
    call $log
    i32.const 3
    i32.const 120
    i32.const 4
    call $log))
