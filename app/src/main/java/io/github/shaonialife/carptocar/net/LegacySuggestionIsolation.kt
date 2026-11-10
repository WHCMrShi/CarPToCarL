package io.github.shaonialife.carptocar.net

/** An unsuccessful upgrade cleanup never authorizes subsequent local network requests. */
internal class LegacySuggestionIsolation {
    private var complete = false
    @Synchronized fun ensure(removeOwned: () -> Boolean): Boolean {
        if (complete) return true
        complete = removeOwned()
        return complete
    }
}
