;; Implements my:project/hello-world. A world's top-level import binds to the
;; core module name "$root", and `name` returns a string, which is two flat
;; values, so it is written through the return pointer this passes in.
;;
;; greet traps unless the host's string arrives as "world", which is what makes
;; the end-to-end test prove the value crossed rather than only that a call
;; happened.
(module
  (import "$root" "name" (func $name (param i32)))
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

  (func (export "greet")
    i32.const 0
    call $name

    ;; The length, written at offset 4, must be that of "world".
    i32.const 4
    i32.load
    i32.const 5
    i32.ne
    if
      unreachable
    end

    ;; The first byte, at the pointer written to offset 0, must be 'w'.
    i32.const 0
    i32.load
    i32.load8_u
    i32.const 0x77
    i32.ne
    if
      unreachable
    end))
