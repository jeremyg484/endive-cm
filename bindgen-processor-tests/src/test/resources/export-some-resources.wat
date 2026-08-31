;; Implements example:exported-resources/export-some-resources, the world of
;; wasmtime's exported-resources bindgen example. The only change is an added
;; "export drops: func() -> u32", because that world exports nothing a test could
;; read a destructor count from.
;;
;; The guest implements the resource, so it mints handles with [resource-new] and
;; is handed the representation directly for a borrowed receiver, which is what
;; lowering a borrow into the component that owns the resource does.
(module
  (import "[export]example:exported-resources/logging" "[resource-new]logger"
    (func $mint (param i32) (result i32)))
  (memory (export "memory") 1)
  (global $next (mut i32) (i32.const 1))
  (global $heap (mut i32) (i32.const 1024))
  (global $drops (mut i32) (i32.const 0))

  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32)
    (local $p i32)
    global.get $heap
    local.set $p
    global.get $heap
    local.get 3
    i32.add
    global.set $heap
    local.get $p)

  (func $slot (param $rep i32) (result i32)
    i32.const 300
    local.get $rep
    i32.const 4
    i32.mul
    i32.add)

  (func (export "example:exported-resources/logging#[constructor]logger") (param i32) (result i32)
    (local $r i32)
    global.get $next
    local.set $r
    global.get $next
    i32.const 1
    i32.add
    global.set $next
    local.get $r
    call $slot
    local.get 0
    i32.store
    local.get $r
    call $mint)

  (func (export "example:exported-resources/logging#[method]logger.get-max-level") (param i32) (result i32)
    local.get 0
    call $slot
    i32.load)

  (func (export "example:exported-resources/logging#[method]logger.set-max-level") (param i32 i32)
    local.get 0
    call $slot
    local.get 1
    i32.store)

  (func (export "example:exported-resources/logging#[method]logger.log") (param i32 i32 i32 i32)
    local.get 0
    call $slot
    local.get 1
    i32.store)

  (func (export "example:exported-resources/logging#[dtor]logger") (param i32)
    global.get $drops
    i32.const 1
    i32.add
    global.set $drops)

  (func (export "drops") (result i32)
    global.get $drops))
