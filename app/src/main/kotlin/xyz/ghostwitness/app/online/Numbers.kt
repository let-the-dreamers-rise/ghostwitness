package xyz.ghostwitness.app.online

import java.math.BigInteger

/** Exact nonnegative conversions available on Android 10 as well as newer releases. */
internal fun BigInteger.safeLong(): Long {
    require(signum() >= 0 && bitLength() <= 63) { "Integer exceeds supported range" }
    return toLong()
}
internal fun BigInteger.safeInt(): Int {
    require(signum() >= 0 && bitLength() <= 31) { "Integer exceeds supported range" }
    return toInt()
}
