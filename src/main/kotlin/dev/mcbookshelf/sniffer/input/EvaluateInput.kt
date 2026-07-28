package dev.mcbookshelf.sniffer.input

import dev.mcbookshelf.sniffer.dispatch.IInput

/**
 * Evaluate a debug expression or Minecraft command in the current scope.
 *
 * @property expression the expression or command string to evaluate.
 * @property context the DAP evaluation context ("repl", "watch", "hover", ...).
 */
data class EvaluateInput(val expression: String, val context: String) : IInput
