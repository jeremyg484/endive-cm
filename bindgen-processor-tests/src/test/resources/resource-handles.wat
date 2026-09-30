;; Implements example:resource-handles/resource-handles, a world built for this
;; test rather than taken from a bindgen! example, because none of those hands
;; out a handle anywhere but from a constructor.
;;
;; Every handle the guest holds here was minted by the host outside a
;; constructor, and each goes back to the host somewhere other than as a
;; method's receiver, so the bindings have to convert handles in general.
;;
;; A handle is an i32 index into the guest's table. poll takes its list as a
;; pointer and a length, one i32 handle per element, and is handed a return
;; area where the host writes its list<u32> as a pointer at 0 and a length at 4,
;; which is why the host needs cabi_realloc to put the list in guest memory.
;;
;; run traps unless poll answers exactly [0] for the one pollable it is given,
;; and returns what splice answered, so a test sees the input stream the guest
;; borrowed arrive as the one the host made.
(module
  (import "example:resource-handles/streams" "open-input" (func $open_input (result i32)))
  (import "example:resource-handles/streams" "open-output" (func $open_output (result i32)))
  (import "example:resource-handles/streams" "[method]input-stream.subscribe"
    (func $subscribe (param i32) (result i32)))
  (import "example:resource-handles/streams" "[method]output-stream.splice"
    (func $splice (param i32 i32 i64) (result i64)))
  (import "example:resource-handles/streams" "poll" (func $poll (param i32 i32 i32)))
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

  (func (export "run") (result i64)
    (local $in i32)
    (local $out i32)
    call $open_input
    local.set $in
    call $open_output
    local.set $out

    ;; poll([in.subscribe()]) -> [0]
    i32.const 400
    local.get $in
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

    ;; out.splice(in, 5)
    local.get $out
    local.get $in
    i64.const 5
    call $splice))
