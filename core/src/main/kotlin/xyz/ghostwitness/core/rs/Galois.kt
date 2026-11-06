package xyz.ghostwitness.core.rs

/** GF(2^8) arithmetic with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D), generator 2. */
internal object Galois {
    private const val POLYNOMIAL = 0x11D
    private const val FIELD_SIZE = 256
    private const val ORDER = 255

    private val exp = IntArray(ORDER * 2)
    private val log = IntArray(FIELD_SIZE)

    /** Full multiplication table: MUL[a][b] = a * b. 64 KiB, built once. */
    val MUL: Array<ByteArray>

    init {
        var x = 1
        for (i in 0 until ORDER) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if ((x and FIELD_SIZE) != 0) x = x xor POLYNOMIAL
        }
        for (i in ORDER until ORDER * 2) exp[i] = exp[i - ORDER]
        MUL = Array(FIELD_SIZE) { a -> ByteArray(FIELD_SIZE) { b -> mul(a, b).toByte() } }
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun inverse(a: Int): Int {
        require(a != 0) { "zero has no inverse in GF(256)" }
        return exp[(ORDER - log[a]) % ORDER]
    }

    fun pow(a: Int, n: Int): Int = when {
        n == 0 -> 1
        a == 0 -> 0
        else -> exp[(log[a] * n) % ORDER]
    }
}
