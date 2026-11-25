package dev.mcbookshelf.sniffer.features.computejava

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.logging.LogUtils
import dev.mcbookshelf.sniffer.command.SnifferCommand
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.arguments.NbtPathArgument
import net.minecraft.commands.arguments.NbtPathArgument.nbtPath
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.commands.arguments.IdentifierArgument.id
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.server.commands.data.DataCommands
import net.minecraft.server.commands.data.StorageDataAccessor
import org.slf4j.Logger

/**
 * The `/compute-java` command, which performs arithmetic operations that are impossible or
 * broken in vanilla Minecraft number providers (overflow, bitwise, int↔float reinterpretation).
 *
 * **Int→int ops** — result returned via Brigadier return code, captured by the caller with
 * `execute store result storage <s> <path> int 1 run compute-java <op> <s1> <p1> <s2> <p2>`:
 *   add, sub, mul, and, or, xor, shl, shr_u, shr_s, compare_unsigned
 *
 * **Bit-reinterpretation ops** — the result tag is written directly into the output storage
 * because the return-code pipeline is integer-only and cannot carry an arbitrary float payload:
 *   int_bits_to_float   <in_s> <in_p>  <out_s> <out_p>
 *   float_to_raw_int_bits <in_s> <in_p> <out_s> <out_p>
 *
 * Unknown op or wrong argument count → warning log, command returns 0 (no crash).
 */
object ComputeJavaCommand : SnifferCommand {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** Ops whose result cannot be carried by the int return code and must be written into an output storage. */
    private val WRITE_OPS = setOf(
        // float→float
        "int_bits_to_float", "int_to_float", "unsigned_int_to_float",
        "fneg", "fadd", "fsub", "fmul", "fdiv",
        // int→long
        "mul_long", "mul_long_unsigned", "mul_long_mixed",
    )

    override fun build(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> =
        literal<CommandSourceStack>("compute-java")
            .then(argument("op", StringArgumentType.word())
                .then(argument("storage1", id())
                    .then(argument("path1", nbtPath())
                        // Unary form: compute-java <op> <storage> <path>
                        // (only used by ops that take a single input and return via return code)
                        .executes { ctx ->
                            val op = StringArgumentType.getString(ctx, "op")
                            if (op in WRITE_OPS) {
                                LOGGER.warn(
                                    "compute-java: op '{}' requires 4 arguments: <in_storage> <in_path> <out_storage> <out_path>",
                                    op
                                )
                                return@executes 0
                            }
                            val a = readStorage(
                                ctx.source,
                                IdentifierArgument.getId(ctx,"storage1"),
                                NbtPathArgument.getPath(ctx, "path1")
                            ) ?: return@executes 0
                            dispatchUnary(op, a)
                        }
                        // Two-storage form: compute-java <op> <storage1> <path1> <storage2> <path2>
                        // For int ops: both are inputs, result via return code.
                        // For unary write ops (int_bits_to_float, fneg): storage1/path1 = input, storage2/path2 = output.
                        .then(argument("storage2", id())
                            .then(argument("path2", nbtPath())
                                .executes { ctx ->
                                    val op = StringArgumentType.getString(ctx, "op")
                                    val storage1 = IdentifierArgument.getId(ctx,"storage1")
                                    val path1 = NbtPathArgument.getPath(ctx, "path1")
                                    val storage2 = IdentifierArgument.getId(ctx,"storage2")
                                    val path2 = NbtPathArgument.getPath(ctx, "path2")
                                    val a = readStorage(ctx.source, storage1, path1) ?: return@executes 0
                                    val binaryWriteOps = setOf("fadd", "fsub", "fmul", "fdiv", "mul_long", "mul_long_unsigned", "mul_long_mixed")
                                    when {
                                        op in binaryWriteOps -> {
                                            LOGGER.warn("compute-java: op '{}' requires 6 arguments: <in1_storage> <in1_path> <in2_storage> <in2_path> <out_storage> <out_path>", op)
                                            return@executes 0
                                        }
                                        op in WRITE_OPS -> dispatchWrite(ctx.source, op, a, null, storage2, path2)
                                        else -> {
                                            val b = readStorage(ctx.source, storage2, path2) ?: return@executes 0
                                            dispatchBinary(op, a, b)
                                        }
                                    }
                                }
                                // Three-storage form: compute-java <op> <s1> <p1> <s2> <p2> <s3> <p3>
                                // For binary write ops (fadd, fsub, fmul, fdiv): s1/p1 and s2/p2 = inputs, s3/p3 = output.
                                .then(argument("storage3", id())
                                    .then(argument("path3", nbtPath())
                                        .executes { ctx ->
                                            val op = StringArgumentType.getString(ctx, "op")
                                            val storage1 = IdentifierArgument.getId(ctx,"storage1")
                                            val path1 = NbtPathArgument.getPath(ctx, "path1")
                                            val storage2 = IdentifierArgument.getId(ctx,"storage2")
                                            val path2 = NbtPathArgument.getPath(ctx, "path2")
                                            val storage3 = IdentifierArgument.getId(ctx,"storage3")
                                            val path3 = NbtPathArgument.getPath(ctx, "path3")
                                            val a = readStorage(ctx.source, storage1, path1) ?: return@executes 0
                                            val b = readStorage(ctx.source, storage2, path2) ?: return@executes 0
                                            if (op !in WRITE_OPS) {
                                                LOGGER.warn("compute-java: op '{}' does not need an output storage", op)
                                                return@executes 0
                                            }
                                            dispatchWrite(ctx.source, op, a, b, storage3, path3)
                                        }
                                    )
                                )
                            )
                        )
                    )
                )
            )

    // --- dispatch ---

    private fun dispatchUnary(op: String, a: Tag): Int {
        val result = ComputeJavaOps.compute(op, a, null)
        if (result == null) {
            LOGGER.warn("compute-java: unknown unary op '{}'", op)
            return 0
        }
        return returnCodeOf(result)
    }

    private fun dispatchBinary(op: String, a: Tag, b: Tag): Int {
        val result = ComputeJavaOps.compute(op, a, b)
        if (result == null) {
            LOGGER.warn("compute-java: unknown binary op '{}'", op)
            return 0
        }
        return returnCodeOf(result)
    }

    private fun dispatchWrite(
        source: CommandSourceStack,
        op: String,
        a: Tag,
        b: Tag?,
        outStorage: Identifier,
        outPath: NbtPathArgument.NbtPath
    ): Int {
        val result = ComputeJavaOps.compute(op, a, b)
        if (result == null) {
            LOGGER.warn("compute-java: op '{}' failed (bad input type or missing operand?)", op)
            return 0
        }
        return if (writeStorage(source, outStorage, outPath, result)) 1 else 0
    }

    // --- storage I/O ---

    private fun readStorage(
        source: CommandSourceStack,
        storageId: Identifier,
        path: NbtPathArgument.NbtPath
    ): Tag? = try {
        DataCommands.getSingleTag(path, StorageDataAccessor(source.server.commandStorage, storageId))
    } catch (e: Exception) {
        LOGGER.warn("compute-java: failed to read {}/{}: {}", storageId, path, e.message)
        null
    }

    private fun writeStorage(
        source: CommandSourceStack,
        storageId: Identifier,
        path: NbtPathArgument.NbtPath,
        value: Tag
    ): Boolean = try {
        val storage = source.server.commandStorage
        val root = storage.get(storageId)
        path.set(root, value.copy())
        storage.set(storageId, root)
        true
    } catch (e: Exception) {
        LOGGER.warn("compute-java: failed to write to {}/{}: {}", storageId, path, e.message)
        false
    }

    /** Maps a numeric tag to the Brigadier return code (truncated to int). */
    private fun returnCodeOf(tag: Tag): Int = (tag as? NumericTag)?.intValue() ?: 0
}
