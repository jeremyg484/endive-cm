;; Implements example:interface-imports/with-imports, the world of wasmtime's
;; interface-imports bindgen example. The only change is an added
;; "export run: func()", because that world imports without exporting and a test
;; needs some way into the guest.
;;
;; run logs twice, at two different levels, so a test can see the enum and the
;; string both arrive and see them arrive in order.
(module
  (import "example:interface-imports/logging" "log" (func $log (param i32 i32 i32)))
  (memory (export "memory") 1)
  (data (i32.const 100) "starting")
  (data (i32.const 120) "done")

  (func (export "run")
    ;; log(warn, "starting")
    i32.const 2
    i32.const 100
    i32.const 8
    call $log
    ;; log(error, "done")
    i32.const 3
    i32.const 120
    i32.const 4
    call $log))
