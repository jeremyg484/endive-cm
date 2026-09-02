;; Implements example:imported-resources/import-some-resources, the world of
;; wasmtime's imported-resources bindgen example. The only change is an added
;; "export run: func()", because that world imports without exporting and a test
;; needs some way into the guest.
;;
;; run builds a logger at "warn", reads the level back, lowers it to "debug",
;; logs a message at "info", then drops the handle. It traps if the level it
;; reads back is not the one it asked for.
(module
  (import "example:imported-resources/logging" "[constructor]logger" (func $new (param i32) (result i32)))
  (import "example:imported-resources/logging" "[method]logger.get-max-level" (func $get (param i32) (result i32)))
  (import "example:imported-resources/logging" "[method]logger.set-max-level" (func $set (param i32 i32)))
  (import "example:imported-resources/logging" "[method]logger.log" (func $log (param i32 i32 i32 i32)))
  (import "example:imported-resources/logging" "[resource-drop]logger" (func $drop (param i32)))
  (memory (export "memory") 1)
  (global $heap (mut i32) (i32.const 1024))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32)
    (local $p i32)
    global.get $heap
    local.set $p
    global.get $heap
    local.get 3
    i32.add
    global.set $heap
    local.get $p)
  (func (export "run")
    (local $h i32)
    i32.const 2
    call $new
    local.set $h
    local.get $h
    call $get
    i32.const 2
    i32.ne
    if
      unreachable
    end
    local.get $h
    i32.const 0
    call $set
    i32.const 512
    i32.const 104
    i32.store8
    i32.const 513
    i32.const 105
    i32.store8
    local.get $h
    i32.const 1
    i32.const 512
    i32.const 2
    call $log
    local.get $h
    call $drop))
