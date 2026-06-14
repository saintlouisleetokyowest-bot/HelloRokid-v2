package com.example.hellorokid.mobile.ui

object CardDisplayHelper {

    fun withSecondary(primary: String, secondary: String): String {
        val main = primary.trim()
        val extra = secondary.trim()
        if (main.isBlank()) return extra
        if (extra.isBlank() || extra.equals(main, ignoreCase = true)) return main
        return "$main\n$extra"
    }
}
