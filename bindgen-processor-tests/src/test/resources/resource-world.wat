;; Implements example:resources/resource-world. A host resource reaches the guest
;; as three core imports, the constructor, each method, and "[resource-drop]file"
;; which the guest calls to give an owned handle back.
;;
;; open-and-measure drops what it made, so the host sees the destructor run.
;; open-and-leak keeps it, so a test can tell the two apart.
(module
  (import "types" "[constructor]file" (func $new (param i32 i32) (result i32)))
  (import "types" "[method]file.name-length" (func $len (param i32) (result i32)))
  (import "types" "[resource-drop]file" (func $drop (param i32)))
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
  (func (export "open-and-measure") (param i32 i32) (result i32)
    (local $h i32) (local $n i32)
    local.get 0
    local.get 1
    call $new
    local.set $h
    local.get $h
    call $len
    local.set $n
    local.get $h
    call $drop
    local.get $n)
  (func (export "open-and-leak") (param i32 i32)
    local.get 0
    local.get 1
    call $new
    drop))
