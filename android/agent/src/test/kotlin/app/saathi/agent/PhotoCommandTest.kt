package app.saathi.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Camera / photo description phrases (English, Hindi, Hinglish) and what must NOT become one. */
class PhotoCommandTest {
    private fun p(text: String) = CommandParser.parse(text)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "take a photo",
            "Take a picture",
            "click a pic",
            "take another photo",
            "what's in front of me?",
            "What is in front of me",
            "whats ahead",
            "what's around me",
            "describe this",
            "Describe what you see",
            "describe my surroundings",
            "what do you see",
            "photo khincho",
            "photo kheencho",
            "ek photo lo",
            "tasveer khinchiye",
            "फ़ोटो खींचो",
            "फोटो लो",
            "सामने क्या है",
            "सामने क्या दिख रहा है",
            "saamne kya hai",
            "samne kya dikh raha hai",
            "aage kya hai",
            "camera se dekho kya hai",
        ],
    )
    fun `camera phrases`(text: String) {
        assertEquals(Command.Photo(PhotoSource.CAMERA, text), p(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "describe my last photo",
            "what's in my last photo?",
            "What is in my latest picture",
            "meri last photo mein kya hai",
            "meri photo mein kya hai",
            "aakhri photo batao",
            "pichli photo mein kya hai",
            "मेरी आखिरी फ़ोटो में क्या है",
            "मेरी फोटो में क्या है",
            "describe the photo I just took",
            "describe my photo",
        ],
    )
    fun `last photo phrases`(text: String) {
        assertEquals(Command.Photo(PhotoSource.LAST_PHOTO, text), p(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "open camera",
            "open photos",
            "open gallery",
            "camera kholo",
            "photos kholo",
            "फ़ोटो खोलो",
            "कैमरा खोलो",
        ],
    )
    fun `opening the camera or photos app is not a description`(text: String) {
        assertFalse(p(text) is Command.Photo, "$text -> ${p(text)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["what's on screen", "describe this screen", "screen pe kya hai", "kya dikh raha hai"])
    fun `screen questions stay screen questions`(text: String) {
        assertTrue(p(text) is Command.ScreenSummary, "$text -> ${p(text)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Read Mom's last message", "take me home", "Mummy ka last message padho", "Help"])
    fun `other commands are untouched`(text: String) {
        assertFalse(p(text) is Command.Photo, "$text -> ${p(text)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["take a photo", "describe my last photo"])
    fun `photo commands are not phone tasks`(text: String) {
        assertFalse(p(text).isTask)
    }
}
