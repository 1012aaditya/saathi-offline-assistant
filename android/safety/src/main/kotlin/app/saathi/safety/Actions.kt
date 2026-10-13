package app.saathi.safety

/**
 * Port of saathi/actions.py: the action schema the model chooses from. The pydantic Field
 * constraints are enforced in init blocks (IllegalArgumentException where pydantic raises
 * ValidationError). String lengths count code points, like pydantic on Python str.
 */
sealed interface Action {
    /** The wire name ("tap", "type", ...), same as the pydantic `type` literal. */
    val type: String
}

data class Tap(val index: Int) : Action {
    override val type: String get() = "tap"

    init {
        require(index >= 0) { "tap.index must be >= 0" }
    }
}

data class TypeText(val text: String, val index: Int? = null) : Action {
    override val type: String get() = "type"

    init {
        require(Py.len(text) in 1..500) { "type.text must be 1..500 characters" }
        require(index == null || index >= 0) { "type.index must be >= 0" }
    }
}

enum class Direction(val wire: String) { UP("up"), DOWN("down") }

data class Scroll(val direction: Direction) : Action {
    override val type: String get() = "scroll"
}

/** App product search via a deep link (Blinkit): lands directly on the results. */
data class Search(val query: String) : Action {
    override val type: String get() = "search"

    init {
        require(Py.len(query) in 1..80) { "search.query must be 1..80 characters" }
    }
}

data object Back : Action {
    override val type: String get() = "back"
}

data object Home : Action {
    override val type: String get() = "home"
}

data class OpenApp(val app: String) : Action {
    override val type: String get() = "open_app"

    init {
        require(app.isNotEmpty()) { "open_app.app must not be empty" }
    }
}

data object Look : Action {
    override val type: String get() = "look"
}

data class AskUser(val question: String) : Action {
    override val type: String get() = "ask_user"

    init {
        require(question.isNotEmpty()) { "ask_user.question must not be empty" }
    }
}

data class Done(val summary: String) : Action {
    override val type: String get() = "done"

    init {
        require(summary.isNotEmpty()) { "done.summary must not be empty" }
    }
}

data class Fail(val reason: String) : Action {
    override val type: String get() = "fail"

    init {
        require(reason.isNotEmpty()) { "fail.reason must not be empty" }
    }
}
