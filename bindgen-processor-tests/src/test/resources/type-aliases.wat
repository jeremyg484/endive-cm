;; Implements example:type-aliases/type-aliases, a world built for this test
;; rather than taken from a bindgen! example, because none of those names a
;; primitive type.
;;
;; instant and duration are both u64, so each crosses as an i64. latest is
;; handed a return area for its stamp, a record whose at is a u64 at 0 and whose
;; seq is a u32 at 8. deadline takes a duration that timer uses from clock.
;;
;; run traps unless now answers 100, elapsed(40) answers 60, latest answers a
;; stamp at 100 with seq 7, and deadline(5) answers 105. It then returns that
;; deadline.
(module
  (import "example:type-aliases/clock" "now" (func $now (result i64)))
  (import "example:type-aliases/clock" "elapsed" (func $elapsed (param i64) (result i64)))
  (import "example:type-aliases/clock" "latest" (func $latest (param i32)))
  (import "example:type-aliases/timer" "deadline" (func $deadline (param i64) (result i64)))
  (memory (export "memory") 1)

  (func (export "run") (result i64)
    ;; now() -> 100
    call $now
    i64.const 100
    i64.ne
    if
      unreachable
    end

    ;; elapsed(40) -> 60
    i64.const 40
    call $elapsed
    i64.const 60
    i64.ne
    if
      unreachable
    end

    ;; latest() -> stamp { at: 100, seq: 7 }
    i32.const 256
    call $latest
    i32.const 256
    i64.load
    i64.const 100
    i64.ne
    if
      unreachable
    end
    i32.const 256
    i32.load offset=8
    i32.const 7
    i32.ne
    if
      unreachable
    end

    ;; deadline(5) -> 105
    i64.const 5
    call $deadline))
