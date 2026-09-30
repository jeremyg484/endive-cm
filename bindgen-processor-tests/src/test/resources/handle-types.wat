;; Implements example:handle-types/handle-types, a world built for this test
;; rather than taken from a bindgen! example, because none of those carries a
;; resource handle inside a record or a variant.
;;
;; open hands back a record holding an owned input-stream, which close hands
;; back again so the host takes ownership of the stream. read fails with a
;; stream-error carrying an owned error, which error is then asked about, so
;; that handle has to be one the error interface accepts.
;;
;; open is handed a return area for its record, the input handle at 0 and the
;; label as a pointer at 4 and a length at 8. read is handed one for its
;; result, whose case is a byte at 0 and whose payload starts at 4. The error
;; case's stream-error puts its own case byte there and the error handle at 8.
;; to-debug-string is handed one for its string, a pointer at 0 and a length
;; at 4. The host puts strings in guest memory, which is why it needs
;; cabi_realloc.
;;
;; run traps unless read fails with last-operation-failed, and returns the
;; length of the carried error's debug string.
(module
  (import "example:handle-types/streams" "open" (func $open (param i32)))
  (import "example:handle-types/streams" "[method]input-stream.read"
    (func $read (param i32 i64 i32)))
  (import "example:handle-types/streams" "close" (func $close (param i32 i32 i32)))
  (import "example:handle-types/error" "[method]error.to-debug-string"
    (func $to_debug_string (param i32 i32)))
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

  (func (export "run") (result i32)
    (local $length i32)
    i32.const 500
    call $open

    ;; open().input.read(5) -> error(last-operation-failed(e))
    i32.const 500
    i32.load
    i64.const 5
    i32.const 520
    call $read
    i32.const 520
    i32.load8_u
    i32.const 1
    i32.ne
    if
      unreachable
    end
    i32.const 520
    i32.load8_u offset=4
    if
      unreachable
    end

    ;; e.to-debug-string().len
    i32.const 520
    i32.load offset=8
    i32.const 540
    call $to_debug_string
    i32.const 540
    i32.load offset=4
    local.set $length

    ;; close(opened)
    i32.const 500
    i32.load
    i32.const 500
    i32.load offset=4
    i32.const 500
    i32.load offset=8
    call $close

    local.get $length))
