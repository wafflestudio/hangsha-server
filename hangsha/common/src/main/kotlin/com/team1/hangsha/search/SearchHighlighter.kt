package com.team1.hangsha.search

object SearchHighlighter {

    fun highlightTitle(title: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return title
        var result = title
        // 긴 토큰부터 처리해서 중첩 마킹 방지
        for (token in tokens.sortedByDescending { it.length }) {
            result = result.replace(token, "<mark>$token</mark>", ignoreCase = true)
        }
        return result
    }

    // raw words(primary) → KiWi tokens(fallback) 우선순위로 하이라이팅
    fun highlightWithFallback(text: String, primary: List<String>, fallback: List<String>): String {
        val result = highlightTitle(text, primary)
        return if (result == text) highlightTitle(text, fallback) else result
    }

    fun extractSnippet(content: String, tokens: List<String>, windowSize: Int = 200): String? {
        if (tokens.isEmpty() || content.isBlank()) return null

        val firstMatchIdx = tokens
            .mapNotNull { token ->
                val i = content.indexOf(token, ignoreCase = true)
                if (i >= 0) i else null
            }
            .minOrNull() ?: return null

        val rawStart = maxOf(0, firstMatchIdx - 60)
        val rawEnd = minOf(content.length, firstMatchIdx + windowSize - 60)

        // 단어 경계에서 자르기
        val start = if (rawStart == 0) 0
                    else (content.lastIndexOf(' ', rawStart).takeIf { it >= 0 } ?: rawStart) + 1
        val end = if (rawEnd >= content.length) content.length
                  else content.indexOf(' ', rawEnd).takeIf { it >= 0 } ?: rawEnd

        val snippet = content.substring(start, end)
        return highlightTitle(snippet, tokens)
    }

    // raw words(primary) → KiWi tokens(fallback) 우선순위로 스니펫 추출
    fun extractSnippetWithFallback(content: String, primary: List<String>, fallback: List<String>): String? {
        return extractSnippet(content, primary) ?: extractSnippet(content, fallback)
    }
}
