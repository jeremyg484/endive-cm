(component
  ;; An inner component that declares a resource, exports it under a name, and exports a
  ;; record type whose field is `own` of that resource. The record's field therefore carries
  ;; a type index that counts in *this* component's space.
  (component $C
    (type $R (resource (rep i32)))
    (export $R2 "r" (type $R))
    (type $rec (record (field "h" (own $R2))))
    (export "rec" (type $rec))

    (core func $new (canon resource.new $R))
    (core module $mc
      (import "" "new" (func $new (param i32) (result i32)))
      (func (export "make") (param i32) (result i32)
        local.get 0
        call $new)
    )
    (core instance $mci (instantiate $mc (with "" (instance (export "new" (func $new))))))
    (func (export "make") (param "rep" u32) (result (own $R2))
      (canon lift (core func $mci "make")))
  )
  (instance $i (instantiate $C))

  ;; Padding so the record's internal index (1, the exported resource) lands on something
  ;; that is NOT a resource type in the outer component's space.
  (type $pad0 (list u8))
  (type $pad1 (list u16))
  (alias export $i "rec" (type $rec2))
  (alias export $i "make" (func $make))

  (core func $lowered-make (canon lower (func $make)))

  ;; Takes the aliased record and hands back the handle it carries.
  (core module $m
    (func (export "f") (param i32) (result i32) (local.get 0))
  )
  (core instance $mi (instantiate $m))
  (func $take (param "x" $rec2) (result u32) (canon lift (core func $mi "f")))
  (core func $lowered-take (canon lower (func $take)))

  (core module $driver
    (import "" "make" (func $make (param i32) (result i32)))
    (import "" "take" (func $take (param i32) (result i32)))
    (func (export "go") (result i32)
      i32.const 7
      call $make
      call $take)
  )
  (core instance $di (instantiate $driver
    (with "" (instance
      (export "make" (func $lowered-make))
      (export "take" (func $lowered-take))))))
  (alias core export $di "go" (core func $go))
  (func (export "go") (result u32) (canon lift (core func $go)))
)
