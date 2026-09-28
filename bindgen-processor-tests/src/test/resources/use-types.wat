;; Implements example:use-types/use-types, a world built for this test rather
;; than taken from a bindgen! example, because none of those uses a type from
;; another interface.
;;
;; Every type crossing here is declared by types and used by logging, so each
;; value only arrives if the bindings resolved the use back to types.
;;
;; latest is handed a return area for its entry, a record whose level enum is a
;; byte at 0 and whose code is a u32 at 4. worst is handed the levels list as a
;; pointer and a length, one byte per enum, and a return area for its
;; option<level>, whose discriminant is a byte at 0 and whose payload is a byte
;; at 1.
;;
;; run traps unless latest answers an entry at error with code 42 and worst
;; answers some(error) for info, error and debug. It then returns the level of
;; that entry, so the used enum crosses in both directions.
(module
  (import "example:use-types/logging" "log" (func $log (param i32 i32 i32)))
  (import "example:use-types/logging" "latest" (func $latest (param i32)))
  (import "example:use-types/logging" "worst" (func $worst (param i32 i32 i32)))
  (memory (export "memory") 1)
  (data (i32.const 100) "starting")
  (data (i32.const 300) "\01\03\00")

  (func (export "run") (result i32)
    ;; log(warn, "starting")
    i32.const 2
    i32.const 100
    i32.const 8
    call $log

    ;; latest() -> entry { level: error, code: 42 }
    i32.const 256
    call $latest
    i32.const 256
    i32.load8_u
    i32.const 3
    i32.ne
    if
      unreachable
    end
    i32.const 256
    i32.load offset=4
    i32.const 42
    i32.ne
    if
      unreachable
    end

    ;; worst([info, error, debug]) -> some(error)
    i32.const 300
    i32.const 3
    i32.const 320
    call $worst
    i32.const 320
    i32.load8_u
    i32.const 1
    i32.ne
    if
      unreachable
    end
    i32.const 320
    i32.load8_u offset=1
    i32.const 3
    i32.ne
    if
      unreachable
    end

    i32.const 256
    i32.load8_u))
