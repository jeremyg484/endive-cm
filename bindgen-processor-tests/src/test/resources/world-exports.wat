;; Implements my:project/hello-world, the world of wasmtime's world-exports
;; bindgen example, unchanged.
;;
;; A function inside an exported interface is a core export named
;; "<interface>#<func>". demo#run calls both of the host's imported functions,
;; handing sha256 three bytes so a test can see the list arrive.
(module
  (import "my:project/host" "gen-random-integer" (func $gen (result i32)))
  (import "my:project/host" "sha256" (func $sha (param i32 i32 i32)))
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
  (func (export "demo#run")
    i32.const 512
    i32.const 10
    i32.store8
    i32.const 513
    i32.const 20
    i32.store8
    i32.const 514
    i32.const 255
    i32.store8
    call $gen
    drop
    i32.const 512
    i32.const 3
    i32.const 0
    call $sha))
