;; Implements my:project/exports-world. A function inside an exported interface is
;; a core export named "<interface>#<func>", while the world's own exports keep
;; their plain names. The imported interface binds to its fully qualified id.
(module
  (import "my:project/host" "gen-random-integer" (func $gen (result i32)))
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
  (func (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add)
  (func (export "count-bytes") (param i32 i32) (result i32)
    local.get 1)
  (func (export "demo#run")
    call $gen
    drop))
