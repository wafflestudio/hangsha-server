package com.team1.hangsha.common.extentions


fun String.getDisplayLength(): Int {
    return this.sumOf { c ->
        if (c in '\uAC00'..'\uD7A3' || c in '\u3131'..'\u318E') 2.toInt() else 1.toInt()
    }
}

fun String.truncateByDisplayLength(maxDisplayLen: Int): String {
    var currentLen = 0
    val sb = StringBuilder()
    for (c in this) {
        val charLen = if (c in '\uAC00'..'\uD7A3' || c in '\u3131'..'\u318E') 2 else 1
        if (currentLen + charLen > maxDisplayLen) break
        currentLen += charLen
        sb.append(c)
    }
    return sb.toString()
}