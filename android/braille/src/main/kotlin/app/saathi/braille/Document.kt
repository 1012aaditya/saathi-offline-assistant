package app.saathi.braille

/** One word and its braille cells (punctuation attached to a word stays in its cells). */
data class Word(val text: String, val cells: List<Cell>)

data class Sentence(val words: List<Word>)

/** Where the reader is: sentence index, word index inside that sentence. */
data class Position(val sentence: Int, val word: Int)

private val SENTENCE_END = Regex("""(?<=[.!?।])\s+""")

/**
 * Text split into sentences and words, so reading can move word by word and a missed word can be
 * repeated on its own instead of restarting the whole sentence.
 */
class Document(text: String) {
    val sentences: List<Sentence> = text.trim()
        .split(SENTENCE_END)
        .map { s -> Sentence(s.split(Regex("\\s+")).filter { it.isNotBlank() }.map { Word(it, encode(it)) }) }
        .filter { it.words.isNotEmpty() }

    val wordCount: Int get() = sentences.sumOf { it.words.size }
    val isEmpty: Boolean get() = sentences.isEmpty()

    fun word(p: Position): Word = sentences[p.sentence].words[p.word]

    /** The last word of a sentence ends with a sentence-end signal. */
    fun endsSentence(p: Position): Boolean = p.word == sentences[p.sentence].words.lastIndex

    /** 1-based number of the word in the whole text (for "word 3 of 12"). */
    fun ordinal(p: Position): Int = sentences.take(p.sentence).sumOf { it.words.size } + p.word + 1

    /** Next word, crossing into the next sentence; null at the end of the text. */
    fun next(p: Position): Position? = when {
        p.word < sentences[p.sentence].words.lastIndex -> p.copy(word = p.word + 1)
        p.sentence < sentences.lastIndex -> Position(p.sentence + 1, 0)
        else -> null
    }

    /** Previous word, crossing into the previous sentence; null at the start. */
    fun previous(p: Position): Position? = when {
        p.word > 0 -> p.copy(word = p.word - 1)
        p.sentence > 0 -> Position(p.sentence - 1, sentences[p.sentence - 1].words.lastIndex)
        else -> null
    }

    fun nextSentence(p: Position): Position? = if (p.sentence < sentences.lastIndex) Position(p.sentence + 1, 0) else null

    /** Start of this sentence, or of the previous one when already at the start. */
    fun previousSentence(p: Position): Position? = when {
        p.word > 0 -> p.copy(word = 0)
        p.sentence > 0 -> Position(p.sentence - 1, 0)
        else -> null
    }
}
