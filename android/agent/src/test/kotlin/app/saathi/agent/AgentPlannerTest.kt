package app.saathi.agent

import app.saathi.safety.Apps
import app.saathi.safety.SafetyGate
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Do it for me" (14 Oct): the model plans one step at a time; every answer is checked (hard rule
 * 10), secrets never reach it, private apps are never sent, and every step goes through the same
 * act() / gate / refusals as a spoken command.
 */
class AgentPlannerTest {
    private val keep = "com.google.android.keep"

    private fun step(json: String) = json.removePrefix(BrainPrompt.PREFILL) // what the engine returns

    @Test
    fun `only valid steps on shown elements are accepted`() {
        val shown = setOf(0, 2)
        assertEquals(AgentStep.Tap(2), AgentPrompt.parse(step("""{"intent":"tap","index":2}"""), shown))
        assertNull(AgentPrompt.parse(step("""{"intent":"tap","index":7}"""), shown), "an index it wasn't shown")
        assertEquals(AgentStep.Tap(2), AgentPrompt.parse(step("""{"intent":"tap","index":"2"}"""), shown), "a quoted number")
        assertNull(AgentPrompt.parse(step("""{"intent":"tap","index":"two"}"""), shown))
        assertNull(AgentPrompt.parse(step("""{"intent":"tap"}"""), shown))
        assertEquals(AgentStep.Type(0, "buy milk"), AgentPrompt.parse(step("""{"intent":"type","index":0,"text":"buy milk"}"""), shown))
        assertNull(AgentPrompt.parse(step("""{"intent":"type","index":0,"text":"482913"}"""), shown), "never types a code")
        assertNull(AgentPrompt.parse(step("""{"intent":"type","index":0,"text":""}"""), shown))
        assertEquals(AgentStep.Scroll(true), AgentPrompt.parse(step("""{"intent":"scroll_down"}"""), shown))
        assertEquals(AgentStep.Back, AgentPrompt.parse(step("""{"intent":"back"}"""), shown))
        assertEquals(AgentStep.Done("Note saved."), AgentPrompt.parse(step("""{"intent":"done","summary":"Note saved."}"""), shown))
        assertEquals(AgentStep.Ask("What should the note say?"), AgentPrompt.parse(step("""{"intent":"ask","question":"What should the note say?"}"""), shown))
        assertNull(AgentPrompt.parse(step("""{"intent":"pay","index":2}"""), shown), "unknown intents are invalid")
        assertNull(AgentPrompt.parse("not json at all", shown))
        assertNull(AgentPrompt.parse(null, shown))
    }

    @Test
    fun `passwords, hidden fields and private-looking text are never shown to the model`() {
        val s = S.screen(
            keep,
            listOf(
                S.el(0, text = "Take a note", clickable = true),
                S.el(1, text = "", desc = "Password", editable = true, password = true),
                S.el(2, text = "Your OTP is 482913"),
                S.el(3, text = "Shopping list", clickable = true),
            ),
        )
        val lines = AgentPrompt.elements(s, hidden = setOf(3))
        assertEquals(listOf(0), lines.map { it.first })
        val p = AgentPrompt.prompt("add a note", "Keep", lines, emptyList())
        assertFalse("482913" in p || "Password" in p || "Shopping list" in p, p)
    }

    private fun run(app: StaticApp, answers: List<String?>, goal: String, mayRead: (String) -> Boolean = { true }): Pair<ScriptIO, FakeBrain> = runBlocking {
        val brain = FakeBrain(answers)
        val io = ScriptIO(emptyList())
        val planner = AgentPlanner(brain, mayRead, timeoutMs = 300)
        GuideSession(GatedDevice(SafetyGate(), app), Lang.EN, io, GuidePolicy(), app.pkg, agent = planner, goal = goal).run()
        io to brain
    }

    @Test
    fun `a note is written step by step through the gate`() {
        val app = StaticApp(
            keep,
            listOf(
                S.el(0, text = "Take a note", clickable = true),
                S.el(1, desc = "Note", cls = "EditText", clickable = true, editable = true),
                S.el(2, text = "Done", clickable = true),
            ),
        )
        val (io, _) = run(
            app,
            listOf(
                step("""{"intent":"tap","index":0}"""),
                step("""{"intent":"type","index":1,"text":"buy milk tomorrow"}"""),
                step("""{"intent":"done","summary":"Your note says buy milk tomorrow."}"""),
            ),
            goal = "add a note saying buy milk tomorrow",
        )
        assertEquals(Tap(0), app.performed[0])
        assertTrue(app.performed.any { it is TypeText && it.text == "buy milk tomorrow" }, app.performed.toString())
        assertTrue(io.said.any { it.startsWith("Done. Your note says buy milk tomorrow.") }, io.said.toString())
    }

    @Test
    fun `a risky button is never pressed even when the model picks it`() {
        val app = StaticApp(
            "com.example.shop",
            listOf(S.el(0, text = "Wireless earphones ₹999"), S.el(1, text = "Buy now", clickable = true)),
        )
        val (io, _) = run(app, listOf(step("""{"intent":"tap","index":1}""")), goal = "buy the earphones")
        assertTrue(app.performed.none { it is Tap }, app.performed.toString())
        assertTrue(io.turns.any { it.kind == GuideTurn.Kind.REFUSED })
    }

    @Test
    fun `a private app's screen is never sent to the model`() {
        val app = StaticApp(Apps.WHATSAPP, listOf(S.el(0, text = "Mummy", clickable = true)))
        val (io, brain) = run(app, listOf(step("""{"intent":"tap","index":0}""")), goal = "message Mummy", mayRead = { it != Apps.WHATSAPP })
        assertTrue(brain.prompts.isEmpty(), "nothing was sent")
        assertTrue(app.performed.isEmpty())
        assertTrue(io.said.any { "private" in it }, io.said.toString())
    }

    @Test
    fun `an invalid answer twice stops the job and says so`() {
        val app = StaticApp(keep, listOf(S.el(0, text = "Take a note", clickable = true)))
        val (io, brain) = run(app, listOf("nonsense", step("""{"intent":"tap","index":42}""")), goal = "add a note")
        assertEquals(2, brain.prompts.size)
        assertTrue(app.performed.isEmpty())
        assertTrue(io.said.any { "couldn't work out" in it }, io.said.toString())
    }
}
