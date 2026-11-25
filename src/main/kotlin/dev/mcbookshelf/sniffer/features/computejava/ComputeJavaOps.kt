package dev.mcbookshelf.sniffer.features.computejava

import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.Tag

/**
 * All operations exposed by `/compute-java`.
 *
 * Int→Int ops return their result as an [IntTag] so the caller can capture it via
 * `execute store result ... int 1`.
 * Bit-reinterpretation ops (int↔float) return a [FloatTag] or [IntTag] that must be written
 * directly into the target storage by [ComputeJavaCommand], because the Minecraft return-code
 * pipeline always goes through an integer and cannot carry a float payload faithfully.
 */
object ComputeJavaOps {

    /**
     * Looks up the operation by name and executes it.
     *
     * @param op   the operation name (e.g. `"and"`, `"int_bits_to_float"`)
     * @param a    first operand tag, always present
     * @param b    second operand tag, or null for unary operations
     * @return the result tag, or null if [op] is unknown or [b] is missing when required
     */
    fun compute(op: String, a: Tag, b: Tag?): Tag? = when (op) {
        // --- binary int ops ---
        "add"              -> intOp(a, b) { x, y -> x + y }
        "sub"              -> intOp(a, b) { x, y -> x - y }
        "mul"              -> intOp(a, b) { x, y -> x * y }
        "and"              -> intOp(a, b) { x, y -> x and y }
        "or"               -> intOp(a, b) { x, y -> x or y }
        "xor"              -> intOp(a, b) { x, y -> x xor y }
        "shl"              -> intOp(a, b) { x, y -> x shl (y and 0x1F) }
        "shr_u"            -> intOp(a, b) { x, y -> x ushr (y and 0x1F) }
        "shr_s"            -> intOp(a, b) { x, y -> x shr (y and 0x1F) }
        "compare_unsigned"  -> intOp(a, b) { x, y -> Integer.compareUnsigned(x, y) }
        "divu"              -> intOp(a, b) { x, y -> if (y == 0) -1 else Integer.divideUnsigned(x, y) }
        "remu"              -> intOp(a, b) { x, y -> if (y == 0) x else Integer.remainderUnsigned(x, y) }
        // --- binary int→long ops (write ops: result stored as LongTag) ---
        "mul_long"          -> longOp(a, b) { x, y -> x.toLong() * y.toLong() }
        "mul_long_unsigned" -> longOp(a, b) { x, y -> Integer.toUnsignedLong(x) * Integer.toUnsignedLong(y) }
        "mul_long_mixed"    -> longOp(a, b) { x, y -> x.toLong() * Integer.toUnsignedLong(y) }
        // --- unary float↔int reinterpretation ops ---
        "int_bits_to_float" -> floatReinterpret(a)
        "float_to_raw_int_bits" -> intReinterpret(a)
        // --- unary int→float / float→int ops (write ops) ---
        "int_to_float"          -> floatOp1Int(a) { x -> x.toFloat() }
        "unsigned_int_to_float" -> floatOp1Int(a) { x -> Integer.toUnsignedLong(x).toFloat() }
        "float_round_to_int"    -> intOp1Float(a) { x -> Math.round(x) }
        "float_to_unsigned_int" -> intOp1Float(a) { x -> floatToUnsignedInt(x) }
        // --- unary float op ---
        "fneg" -> floatOp1(a) { x -> -x }
        // --- binary float ops ---
        "fadd" -> floatOp2(a, b) { x, y -> x + y }
        "fsub" -> floatOp2(a, b) { x, y -> x - y }
        "fmul" -> floatOp2(a, b) { x, y -> x * y }
        "fdiv" -> floatOp2(a, b) { x, y -> x / y }
        else -> null
    }

    // --- helpers ---

    private inline fun intOp(a: Tag, b: Tag?, crossinline fn: (Int, Int) -> Int): Tag? {
        if (b == null) return null
        val ia = (a as? NumericTag)?.intValue() ?: return null
        val ib = (b as? NumericTag)?.intValue() ?: return null
        return IntTag.valueOf(fn(ia, ib))
    }

    /** int_bits_to_float: reads an int, produces a float whose NBT representation carries the right bits. */
    private fun floatReinterpret(a: Tag): Tag? {
        val bits = (a as? NumericTag)?.intValue() ?: return null
        return FloatTag.valueOf(java.lang.Float.intBitsToFloat(bits))
    }

    /** float_to_raw_int_bits: reads a float, produces the int whose value equals those raw bits. */
    private fun intReinterpret(a: Tag): Tag? {
        val f = (a as? NumericTag)?.floatValue() ?: return null
        return IntTag.valueOf(java.lang.Float.floatToRawIntBits(f))
    }

    private inline fun floatOp1(a: Tag, crossinline fn: (Float) -> Float): Tag? {
        val fa = (a as? NumericTag)?.floatValue() ?: return null
        return FloatTag.valueOf(fn(fa))
    }

    private inline fun floatOp2(a: Tag, b: Tag?, crossinline fn: (Float, Float) -> Float): Tag? {
        if (b == null) return null
        val fa = (a as? NumericTag)?.floatValue() ?: return null
        val fb = (b as? NumericTag)?.floatValue() ?: return null
        return FloatTag.valueOf(fn(fa, fb))
    }

    private inline fun longOp(a: Tag, b: Tag?, crossinline fn: (Int, Int) -> Long): Tag? {
        if (b == null) return null
        val ia = (a as? NumericTag)?.intValue() ?: return null
        val ib = (b as? NumericTag)?.intValue() ?: return null
        return LongTag.valueOf(fn(ia, ib))
    }

    private inline fun floatOp1Int(a: Tag, crossinline fn: (Int) -> Float): Tag? {
        val ia = (a as? NumericTag)?.intValue() ?: return null
        return FloatTag.valueOf(fn(ia))
    }

    private inline fun intOp1Float(a: Tag, crossinline fn: (Float) -> Int): Tag? {
        val fa = (a as? NumericTag)?.floatValue() ?: return null
        return IntTag.valueOf(fn(fa))
    }

    /** RISC-V fcvt.wu.s semantics: NaN or ≥ 2^32 → 0xFFFFFFFF, < 0 → 0. */
    private fun floatToUnsignedInt(f: Float): Int = when {
        f.isNaN() || f >= 4294967296.0f -> -1 // 0xFFFFFFFF as signed int
        f < 0.0f -> 0
        else -> java.lang.Long.valueOf(f.toLong()).toInt()
    }
}
