;; Implements example:world-exports/with-exports, the world of wasmtime's
;; all-world-export-kinds bindgen example, unchanged.
;;
;; A world's own export keeps its plain name, a function inside an exported
;; interface is a core export named "<interface>#<func>", and an interface
;; exported by id uses that whole id as the prefix.
;;
;; bytes-to-string and duration-to-string trap unless they are handed the values
;; the test passes, which is what shows u64 and u32 crossing intact.
(module
  (import "$root" "log" (func $log (param i32 i32)))
  (memory (export "memory") 1)
  (data (i32.const 100) "ok")
  (data (i32.const 110) "kb")
  (data (i32.const 120) "5s")
  (data (i32.const 130) "ran")
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

  ;; An exported function returning a string hands back a pointer to the
  ;; (pointer, length) pair, rather than being given somewhere to write it.
  (func $pair (param $at i32) (param $ptr i32) (param $len i32) (result i32)
    local.get $at
    local.get $ptr
    i32.store
    local.get $at
    local.get $len
    i32.store offset=4
    local.get $at)

  (func (export "run")
    i32.const 130
    i32.const 3
    call $log)

  (func (export "environment#get") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    call $log
    i32.const 200
    i32.const 100
    i32.const 2
    call $pair)

  (func (export "environment#set") (param i32 i32 i32 i32)
    local.get 2
    local.get 3
    call $log)

  (func (export "example:world-exports/units#bytes-to-string") (param i64) (result i32)
    local.get 0
    i64.const 1024
    i64.ne
    if
      unreachable
    end
    i32.const 208
    i32.const 110
    i32.const 2
    call $pair)

  (func (export "example:world-exports/units#duration-to-string") (param i64 i32) (result i32)
    local.get 0
    i64.const 5
    i64.ne
    if
      unreachable
    end
    local.get 1
    i32.const 500
    i32.ne
    if
      unreachable
    end
    i32.const 216
    i32.const 120
    i32.const 2
    call $pair))
