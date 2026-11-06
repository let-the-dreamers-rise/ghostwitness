package xyz.ghostwitness.core.rs

/** Immutable matrix over GF(256). Operations return new matrices. */
internal class GfMatrix private constructor(val rows: Int, val cols: Int, private val cells: Array<IntArray>) {
    operator fun get(row: Int, col: Int): Int = cells[row][col]

    fun row(index: Int): IntArray = cells[index].copyOf()

    fun times(other: GfMatrix): GfMatrix {
        require(cols == other.rows) { "matrix shapes do not match" }
        return GfMatrix(rows, other.cols, Array(rows) { r ->
            IntArray(other.cols) { c ->
                (0 until cols).fold(0) { acc, i -> acc xor Galois.mul(cells[r][i], other.cells[i][c]) }
            }
        })
    }

    fun subRows(indices: List<Int>): GfMatrix = GfMatrix(indices.size, cols, Array(indices.size) { cells[indices[it]].copyOf() })

    /** Gauss-Jordan inversion on a private working copy. Throws if the matrix is singular. */
    fun inverse(): GfMatrix {
        require(rows == cols) { "only square matrices can be inverted" }
        val n = rows
        val work = Array(n) { r -> IntArray(2 * n) { c -> if (c < n) cells[r][c] else if (c - n == r) 1 else 0 } }
        for (col in 0 until n) {
            val pivot = (col until n).firstOrNull { work[it][col] != 0 }
                ?: throw IllegalArgumentException("matrix is singular")
            if (pivot != col) {
                val tmp = work[pivot]
                work[pivot] = work[col]
                work[col] = tmp
            }
            val scale = Galois.inverse(work[col][col])
            for (c in 0 until 2 * n) work[col][c] = Galois.mul(work[col][c], scale)
            for (r in 0 until n) {
                val factor = work[r][col]
                if (r != col && factor != 0) {
                    for (c in 0 until 2 * n) work[r][c] = work[r][c] xor Galois.mul(factor, work[col][c])
                }
            }
        }
        return GfMatrix(n, n, Array(n) { r -> IntArray(n) { c -> work[r][n + c] } })
    }

    companion object {
        fun vandermonde(rows: Int, cols: Int): GfMatrix =
            GfMatrix(rows, cols, Array(rows) { r -> IntArray(cols) { c -> Galois.pow(r, c) } })
    }
}
