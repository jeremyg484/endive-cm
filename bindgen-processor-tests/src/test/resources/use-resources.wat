;; Implements example:use-resources/use-resources, a world built for this test
;; rather than taken from a bindgen! example, because none of those uses a
;; resource from another interface.
;;
;; streams uses error and pollable from the interfaces declaring them, so a
;; handle streams hands out has to be one those interfaces accept, and one they
;; hand out has to be one streams accepts. Each handle here crosses that way.
;;
;; A handle is an i32 index into the guest's table. poll takes its list as a
;; pointer and a length, one i32 handle per element, and is handed a return
;; area for its list<u32>, a pointer at 0 and a length at 4. to-debug-string is
;; handed a return area for its string the same way. The host puts both in
;; guest memory, which is why it needs cabi_realloc.
;;
;; run traps unless poll answers exactly [0] for the pollable streams handed
;; out, and unless streams recognises the error it handed out when that error
;; is borrowed back. It returns the length of the error's debug string, which
;; the error interface answers for a handle streams minted.
(module
  (import "example:use-resources/streams" "open-input" (func $open_input (result i32)))
  (import "example:use-resources/streams" "[method]input-stream.subscribe"
    (func $subscribe (param i32) (result i32)))
  (import "example:use-resources/streams" "last-error" (func $last_error (result i32)))
  (import "example:use-resources/streams" "recorded" (func $recorded (param i32) (result i32)))
  (import "example:use-resources/poll" "poll" (func $poll (param i32 i32 i32)))
  (import "example:use-resources/error" "[method]error.to-debug-string"
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
    (local $error i32)

    ;; poll([open-input().subscribe()]) -> [0]
    i32.const 400
    call $open_input
    call $subscribe
    i32.store
    i32.const 400
    i32.const 1
    i32.const 420
    call $poll
    i32.const 420
    i32.load offset=4
    i32.const 1
    i32.ne
    if
      unreachable
    end
    i32.const 420
    i32.load
    i32.load
    if
      unreachable
    end

    ;; recorded(last-error()) -> true
    call $last_error
    local.set $error
    local.get $error
    call $recorded
    i32.eqz
    if
      unreachable
    end

    ;; last-error().to-debug-string().len
    local.get $error
    i32.const 440
    call $to_debug_string
    i32.const 440
    i32.load offset=4))
