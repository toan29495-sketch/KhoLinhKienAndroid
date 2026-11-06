package vn.kholinhkien

fun main() {
    val admin = "00000000-0000-0000-0000-000000000001"
    val ktv = "00000000-0000-0000-0000-000000000002"
    check(MovementOwnerResolver.resolve(admin, null, false) == admin)
    check(MovementOwnerResolver.resolve(admin, admin, false) == admin)
    check(runCatching { MovementOwnerResolver.resolve(admin, ktv, false) }.isFailure)
    check(MovementOwnerResolver.resolve(admin, ktv, true) == ktv)
    check(runCatching { MovementOwnerResolver.resolve(admin, null, true) }.isFailure)
    check(runCatching { MovementOwnerResolver.resolve(admin, "not-a-uuid", true) }.isFailure)
    check(MovementOwnerResolver.resolve(admin, "00000000-0000-0000-0000-00000000000A", true) == "00000000-0000-0000-0000-00000000000a")
    check(runCatching { MovementOwnerResolver.resolve("", ktv, true) }.isFailure)
    println("MOVEMENT_OWNER_RESOLVER_JVM=8/8 PASS")
}
