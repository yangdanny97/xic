use class_implements_interface

// Can define fields
class A {
    a: int
    fa(p: int): bool { return true }
}

// Also checks that order of methods doesn't matter
class B extends A {
    b_, b: int
    fb_(p: bool) {}
    fb(p: int): int { return 0 }
}

class LocalClass extends B {
    asdf:int
}

local_f() {}
