package app.saathi.braille

/**
 * The Learn-braille course. Pure data so it can be tested; the app turns each [Step] into a screen.
 *
 * Teaching order is the classic one: a–j first (top four dots only), then every other letter is
 * built from them — k–t add dot 3, u–z add dots 3 and 6 (w is the exception) — then numbers
 * (number sign + a–j), signs, words, sentences, a story.
 */
sealed interface Step {
    /** Explanation, read aloud. */
    data class Say(val text: String) : Step

    /** Game: find dot [dot] on the big cell. */
    data class FindDot(val dot: Int) : Step

    /** Introduce one character: feel it, hear its rhythm, learn its hint. */
    data class Meet(val char: Char) : Step

    /** Show how [to] is built from [from] (e.g. a → k adds dot 3). */
    data class Compare(val from: Char, val to: Char) : Step

    /** Play an example in rhythm with an explanation. */
    data class Listen(val text: String, val explain: String) : Step

    /** Listen and pick the right answer from 4. [pass] right answers out of [questions] to continue. */
    data class Quiz(val pool: List<String>, val questions: Int, val pass: Int) : Step

    /** Make the letter: turn the right dots on, then check. */
    data class Build(val char: Char) : Step

    /** Read text with the full rhythm reader. */
    data class Read(val text: String, val explain: String) : Step
}

data class Lesson(val id: Int, val unit: String, val title: String, val summary: String, val steps: List<Step>)

private fun letters(range: CharRange) = range.map { it.toString() }

val LESSONS: List<Lesson> = listOf(
    Lesson(
        1, "Start here", "The six dots", "Where each dot sits. A game: find the dot.",
        listOf(
            Step.Say("Braille is read in cells. Each cell has six dot places, in two columns of three, like a domino standing up."),
            Step.Say("The left column is dots 1, 2 and 3, from top to bottom. The right column is dots 4, 5 and 6."),
            Step.FindDot(1), Step.FindDot(4), Step.FindDot(2), Step.FindDot(5), Step.FindDot(3), Step.FindDot(6),
            Step.Say("Well done. Every letter is some of these six dots. Next, two ways to read them on a phone."),
        ),
    ),
    Lesson(
        2, "Start here", "Two ways to read", "Touch: slide to feel. Rhythm: dots play in time.",
        listOf(
            Step.Say("Touch reading: one big cell fills the screen. Slide one finger slowly. The phone buzzes, or plays a tone, while you are on a raised dot."),
            Step.Meet('a'),
            Step.Say("Rhythm reading: the six dots play in order, 1 to 6. A raised dot is a strong pulse. An empty dot is a faint tick, so you can count."),
            Step.Listen("a", "Letter a: one strong pulse, then faint ticks."),
            Step.Say("Listen for the short pause after dot 3. It splits the left column from the right, so you only ever count to three."),
            Step.Listen("l", "Letter l: the whole left column. Strong, strong, strong. Pause. Faint, faint, faint."),
            Step.Listen("c", "Letter c: dots 1 and 4. Strong, faint, faint. Pause. Strong, faint, faint."),
        ),
    ),
    Lesson(
        3, "Letters a–j", "a to e", "Your first five letters, then a short quiz.",
        listOf(
            Step.Say("Letters a to j only use the top four dots: 1, 2, 4 and 5. Learn these ten well; every other letter is built from them."),
            Step.Meet('a'), Step.Meet('b'), Step.Meet('c'), Step.Meet('d'), Step.Meet('e'),
            Step.Quiz(letters('a'..'e'), 8, 6),
        ),
    ),
    Lesson(
        4, "Letters a–j", "f to j", "Five more letters, then a quiz on all ten.",
        listOf(
            Step.Say("Five more letters. They still use only dots 1, 2, 4 and 5."),
            Step.Meet('f'), Step.Meet('g'), Step.Meet('h'), Step.Meet('i'), Step.Meet('j'),
            Step.Quiz(letters('a'..'j'), 10, 7),
        ),
    ),
    Lesson(
        5, "Letters a–j", "Make the letters", "Turn the right dots on yourself. Writing makes letters stick.",
        listOf(
            Step.Say("Now you build the letters. I say a letter; you turn its dots on, then press Check."),
            Step.Build('a'), Step.Build('c'), Step.Build('b'), Step.Build('d'), Step.Build('g'), Step.Build('j'), Step.Build('e'), Step.Build('h'),
        ),
    ),
    Lesson(
        6, "Letters k–z", "k to t: add dot 3", "Ten new letters from one rule.",
        listOf(
            Step.Say("Here is the trick. Take a to j and add dot 3, and you get k to t. a becomes k, b becomes l, and so on."),
            Step.Compare('a', 'k'), Step.Compare('b', 'l'), Step.Compare('c', 'm'), Step.Compare('d', 'n'), Step.Compare('e', 'o'),
            Step.Say("The same works for the next five: f to j plus dot 3 give p, q, r, s, t."),
            Step.Compare('f', 'p'), Step.Compare('g', 'q'), Step.Compare('h', 'r'), Step.Compare('i', 's'), Step.Compare('j', 't'),
            Step.Quiz(letters('k'..'t'), 10, 7),
        ),
    ),
    Lesson(
        7, "Letters k–z", "u to z: add dots 3 and 6", "Five more from a rule, and why w is different.",
        listOf(
            Step.Say("Take a to e and add dots 3 and 6: you get u, v, x, y, z. Notice w is missing."),
            Step.Compare('a', 'u'), Step.Compare('b', 'v'), Step.Compare('c', 'x'), Step.Compare('d', 'y'), Step.Compare('e', 'z'),
            Step.Say("Braille was invented in France, where w was rarely used, so w was added later. It is j plus dot 6."),
            Step.Meet('w'),
            Step.Quiz(listOf("u", "v", "w", "x", "y", "z"), 8, 6),
        ),
    ),
    Lesson(
        8, "Letters k–z", "The whole alphabet", "Mixed quiz on all 26 letters, then build a few.",
        listOf(
            Step.Say("All 26 letters, mixed. Take your time; use Play again as often as you like."),
            Step.Quiz(letters('a'..'z'), 15, 11),
            Step.Build('k'), Step.Build('u'), Step.Build('w'), Step.Build('s'),
        ),
    ),
    Lesson(
        9, "Numbers and signs", "Numbers", "The number sign turns a to j into 1 to 0.",
        listOf(
            Step.Say("Numbers reuse a to j. A number sign comes first: dots 3, 4, 5 and 6. After it, a means 1, b means 2, up to j, which means 0."),
            Step.Listen("1", "The number sign, then a: the number 1."),
            Step.Listen("25", "One number sign, then b and e: 25. The sign covers the whole number."),
            Step.Quiz(letters('0'..'9'), 10, 7),
            Step.Read("My code is 4821.", "A sentence with a number. Notice the number sign before 4821."),
        ),
    ),
    Lesson(
        10, "Numbers and signs", "Capitals and punctuation", "Dot 6 for capitals; full stop, comma, question mark.",
        listOf(
            Step.Say("A capital letter has a capital sign in front: dot 6 alone. It matters in passwords."),
            Step.Listen("A", "Capital sign, then a."),
            Step.Listen("Hi", "Capital sign, h, i."),
            Step.Say("Punctuation sits in the lower part of the cell."),
            Step.Listen(".", "Full stop: dots 2, 5 and 6."),
            Step.Listen(",", "Comma: dot 2 alone."),
            Step.Listen("?", "Question mark: dots 2, 3 and 6."),
            Step.Quiz(listOf(".", ",", "?", "!", "-"), 8, 6),
        ),
    ),
    Lesson(
        11, "Words and reading", "First words", "Hear where one word ends and the next begins.",
        listOf(
            Step.Say("Between letters there is a short silence. After a word, a longer silence. That is how you hear where a word ends."),
            Step.Listen("cat", "c, a, t, then the long word silence."),
            Step.Listen("dog", "d, o, g."),
            Step.Quiz(listOf("cat", "dog", "sun", "bed", "hat", "map", "cup", "pen"), 8, 6),
        ),
    ),
    Lesson(
        12, "Words and reading", "Sentences", "The sentence-end pulse, and the reading controls.",
        listOf(
            Step.Say("At the end of a sentence you get a long low pulse. Then a new sentence begins."),
            Step.Listen("Hi.", "Hi, full stop, then the long low pulse."),
            Step.Read(
                "The cat sat. A dog ran.",
                "Next goes to the next word. Repeat plays the word again. Read on keeps going. Tap the reading area to repeat; hold it to hear and spell.",
            ),
        ),
    ),
    Lesson(
        13, "Words and reading", "A short story", "Read four sentences with Read on.",
        listOf(
            Step.Say("Your first story. Press Read on and let it flow. If you miss a word, press Repeat: only that word plays again."),
            Step.Read("Mina has a red bag. She walks to the shop. She buys milk and bread. Then she goes home.", "Read on plays word after word. Slower and Faster change the speed."),
            Step.Say("You have finished the course. Keep practising with Review, and read real text from the Braille screen."),
        ),
    ),
)

private val AJ_HINTS = mapOf(
    'a' to "the first letter, the first dot",
    'b' to "a short line down the left side",
    'c' to "a line across the top",
    'd' to "c with a step down on the right",
    'e' to "a slant from top left down to the right",
    'f' to "b with dot 4: a little flag",
    'g' to "the whole top square, all four top dots",
    'h' to "b with dot 5",
    'i' to "a slant from dot 2 up to dot 4, like e flipped",
    'j' to "i with dot 5 added",
)

/** "dots 1, 4 and 5" for a cell. */
fun describeDots(cell: Cell): String {
    val d = (1..6).filter { cell.raised(it) }
    return when (d.size) {
        0 -> "no dots"
        1 -> "dot ${d[0]}"
        else -> "dots " + d.dropLast(1).joinToString(", ") + " and " + d.last()
    }
}

/** How to remember a character: a picture for a–j, the building rule for the rest. */
fun memoryHint(ch: Char): String? {
    val c = ch.lowercaseChar()
    return when {
        c in AJ_HINTS -> AJ_HINTS.getValue(c)
        c in 'k'..'t' -> "${'a' + (c - 'k')} plus dot 3"
        c == 'w' -> "j plus dot 6 (added later)"
        c in "uvxyz" -> "${"abcde"["uvxyz".indexOf(c)]} plus dots 3 and 6"
        c in '1'..'9' -> "number sign, then ${'a' + (c - '1')}"
        c == '0' -> "number sign, then j"
        else -> null
    }
}

/** Spoken name of a quiz item or character ("letter b", "number 7", "full stop", "the word cat"). */
fun itemName(item: String): String = when {
    item.length == 1 && item[0].isLetter() -> "letter $item"
    item.all { it.isDigit() } -> "number $item"
    item.length == 1 -> encode(item).first().spoken
    else -> "the word $item"
}
