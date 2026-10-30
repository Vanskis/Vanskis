package com.vanskis.liquidpocket.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * The calculate tool's side of the math sandbox: assets/mathjs/runner.js defines lpRun after
 * math.js, and this builds the call to it. The sandbox has no files or network.
 */
object MathScript {
    const val TOOL = "calculate"
    const val LIBRARY = "mathjs/math.js"
    const val RUNNER = "mathjs/runner.js"
    const val MAX_EXPRESSIONS = 20

    val MODES = setOf("exact", "fraction")

    /**
     * The expressions to evaluate in order: the "expressions" array, else the "expression" string.
     * A string is one expression even with commas in it (max(1, 2)); lines in it are separate ones.
     */
    fun expressions(args: JsonObject): List<String> {
        val list = (args["expressions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: listOfNotNull(args.string("expressions"), args.string("expression"))
        return list.flatMap { it.lines() }.map(String::trim).filter(String::isNotEmpty).take(MAX_EXPRESSIONS)
    }

    /** The JavaScript that evaluates [expressions] in [mode] and gives back its JSON answer. */
    fun call(expressions: List<String>, mode: String?): String {
        val list = buildJsonArray { expressions.forEach { add(it) } }
        return "lpRun($list, ${JsonPrimitive(mode?.takeIf { it in MODES } ?: "exact")})"
    }
}
