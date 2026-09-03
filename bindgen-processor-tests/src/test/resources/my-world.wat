;; Implements example:imports/my-world. The world's own imports bind to the core
;; module name "$root", while an imported interface binds to the name the world
;; imports it under.
;;
;; run drives everything the world imports. It asks the host for a greeting,
;; hands that same string straight back through log, and then calls tick on the
;; imported interface, so a test can observe all three.
(module
  (import "$root" "greet" (func $greet (param i32)))
  (import "$root" "log" (func $log (param i32 i32)))
  (import "my-custom-host" "tick" (func $tick))
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
    i32.const 0
    call $greet
    i32.const 0
    i32.load
    i32.const 4
    i32.load
    call $log
    call $tick))
