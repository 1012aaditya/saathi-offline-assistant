package app.saathi.agent

import app.saathi.safety.Bounds
import app.saathi.safety.payeeMatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamesTest {
    @Test
    fun transliteration() {
        assertEquals("rahul", Names.transliterate("राहुल"))
        assertEquals("priya", Names.transliterate("प्रिया"))
        assertEquals("kamal", Names.transliterate("कमल"))
        assertEquals("sunil kumar", Names.transliterate("सुनील कुमार"))
    }

    @Test
    fun `spoken spellings match on-screen names via the safety matcher`() {
        assertTrue(payeeMatches(Names.contextFor("मम्मी", ""), "Mom"))
        assertTrue(payeeMatches(Names.contextFor("Mummy", ""), "Mom ❤️"))
        assertTrue(payeeMatches(Names.contextFor("राहुल", ""), "Rahul Sharma"))
        assertTrue(payeeMatches(Names.contextFor("papa", ""), "Dad"))
        assertFalse(payeeMatches(Names.contextFor("Priya", ""), "Rahul Sharma"))
    }
}

class NormalizerTest {
    private fun n(
        cls: String = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        rid: String? = null,
        y: Int = 0,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        showingHint: Boolean = false,
        visible: Boolean = true,
        children: List<RawNode> = emptyList(),
    ) = RawNode(cls, text, desc, hint, rid?.let { "com.whatsapp:id/$it" }, Bounds(0, y, 1000, y + 50), clickable, editable, password, visible, showingHint, children)

    @Test
    fun `message rows are tagged from me or them by the status tick`() {
        val tree = n(
            cls = "android.widget.FrameLayout",
            children = listOf(
                n(rid = "conversation_row_text", cls = "android.view.ViewGroup", children = listOf(n(text = "hello from them", rid = "message_text", y = 100), n(text = "10:01", rid = "date", y = 100))),
                n(rid = "conversation_row_text", cls = "android.view.ViewGroup", children = listOf(n(text = "my reply", rid = "message_text", y = 200), n(desc = "Read", rid = "status", y = 200))),
            ),
        )
        val els = Normalizer.normalize(listOf(tree), "com.whatsapp").screen.elements
        val msgs = els.filter { it.rid == "message_text" }
        assertEquals(listOf("them", "me"), msgs.map { it.sender })
        assertEquals("", els.first { it.rid == "date" }.sender)
    }

    @Test
    fun `password text is never read and hints are not input`() {
        val tree = n(
            cls = "android.widget.FrameLayout",
            children = listOf(
                n(cls = "android.widget.EditText", text = "secret123", password = true, editable = true, rid = "pwd", y = 10),
                n(cls = "android.widget.EditText", text = "Message", hint = "Message", showingHint = true, editable = true, rid = "entry", y = 100),
            ),
        )
        val els = Normalizer.normalize(listOf(tree), "x").screen.elements
        assertEquals("", els[0].text)
        assertTrue(els[0].password)
        assertEquals("", els[1].text)
        assertEquals("Message", els[1].desc)
    }

    @Test
    fun `wrappers drop, leaf cards borrow text, invisible nodes skip`() {
        val tree = n(
            cls = "android.widget.FrameLayout",
            children = listOf(
                n(cls = "android.widget.LinearLayout", clickable = true, y = 10, children = listOf(n(text = "Inner", clickable = true, y = 10))),
                n(cls = "android.widget.LinearLayout", clickable = true, y = 100, children = listOf(n(text = "Card title", y = 100), n(text = "Sub", y = 120))),
                n(text = "hidden", visible = false, y = 300),
                n(text = " icon", y = 400),
            ),
        )
        val snap = Normalizer.normalize(listOf(tree), "x")
        val texts = snap.screen.elements.map { it.text }
        assertEquals(listOf("Inner", "Card title · Sub", "Card title", "Sub", "icon"), texts)
        assertEquals(snap.screen.elements.size, snap.handles.size)
        assertEquals(snap.screen.elements.indices.toList(), snap.screen.elements.map { it.index })
    }

    @Test
    fun `hash follows texts and package`() {
        val a = S.el(0, text = "a")
        assertEquals(Normalizer.screenHash("p", listOf(a)), Normalizer.screenHash("p", listOf(a)))
        assertNotEquals(Normalizer.screenHash("p", listOf(a)), Normalizer.screenHash("q", listOf(a)))
        assertNotEquals(Normalizer.screenHash("p", listOf(a)), Normalizer.screenHash("p", listOf(S.el(0, text = "b"))))
    }
}
