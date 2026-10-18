;; Implements endive:wasi-test/random, whose exports each return what one
;; wasi:random function gave the guest.
;;
;; A list or a tuple of two u64 values flattens to more than one value, so the
;; import is handed a return area and the export returns a pointer to one. The
;; same area serves both. A list is a pointer and a length, which the host
;; allocates through cabi_realloc.
(module
  (import "wasi:random/random@0.2.12" "get-random-bytes"
    (func $get-random-bytes (param i64 i32)))
  (import "wasi:random/random@0.2.12" "get-random-u64"
    (func $get-random-u64 (result i64)))
  (import "wasi:random/insecure@0.2.12" "get-insecure-random-bytes"
    (func $get-insecure-random-bytes (param i64 i32)))
  (import "wasi:random/insecure@0.2.12" "get-insecure-random-u64"
    (func $get-insecure-random-u64 (result i64)))
  (import "wasi:random/insecure-seed@0.2.12" "insecure-seed"
    (func $insecure-seed (param i32)))
  (memory (export "memory") 1)
  (global $heap (mut i32) (i32.const 1024))

  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32)
    (local $p i32)
    global.get $heap
    local.set $p
    global.get $heap
    local.get 3
    i32.const 7
    i32.add
    i32.const -8
    i32.and
    i32.add
    global.set $heap
    local.get $p)

  (func (export "random-bytes") (param $len i64) (result i32)
    local.get $len
    i32.const 256
    call $get-random-bytes
    i32.const 256)

  (func (export "random-u64") (result i64)
    call $get-random-u64)

  (func (export "insecure-bytes") (param $len i64) (result i32)
    local.get $len
    i32.const 256
    call $get-insecure-random-bytes
    i32.const 256)

  (func (export "insecure-u64") (result i64)
    call $get-insecure-random-u64)

  (func (export "insecure-seed") (result i32)
    i32.const 256
    call $insecure-seed
    i32.const 256))
