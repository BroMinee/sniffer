package dev.mcbookshelf.sniffer.features.evaluate

import dev.mcbookshelf.sniffer.dispatch.Output

/**
 * Result of evaluating a debug expression.
 *
 * @property result the evaluated value as a string, or the message of the error that stopped it
 * @property variablesReference id to expand a compound result with, `0` when the result is a leaf
 * @property feedback messages the command wrote back, only populated when the expression was a raw command
 * @author theogiraudet
 */
data class EvaluateOutput(
    val result: String,
    val variablesReference: Int = 0,
    val feedback: List<String> = emptyList(),
) : Output
