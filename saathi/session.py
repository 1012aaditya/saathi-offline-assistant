"""Voice-session controls and follow-ups (pure functions, no I/O).

Controls the user can say between tasks:
  repeat  "phir se bolo", "dobara", "repeat"        → say the last answer again
  slower  "dheere bolo", "slow"                      → speak slower
  faster  "tez bolo", "jaldi bolo", "fast"           → speak faster
  help    "madad", "kya kar sakte ho", "help"        → list what Saathi can do
  quit    "bas", "band karo", "bye", "exit"          → end the session

Follow-ups reuse the last contact so the user needn't repeat the name:
  after "Mummy ka last message padho":
    "reply karo: main aa raha hoon"  → "Mummy ko reply karo: main aa raha hoon"
    "aur pichle message padho"       → "WhatsApp par Mummy ke pichle message padho"
"""

import re
from dataclasses import dataclass, field

_CONTROLS: list[tuple[str, re.Pattern]] = [
    ("repeat", re.compile(r"(?i)^(phir se|fir se|dobara|repeat|again)\b|फिर से|दोबारा")),
    ("slower", re.compile(r"(?i)\b(dheere|dhire|slow(er)?)\b|धीरे")),
    ("faster", re.compile(r"(?i)\b(tez|jaldi|fast(er)?)\s*(bolo|boliye)?\b|तेज़|तेज|जल्दी")),
    ("help", re.compile(r"(?i)\b(madad|help)\b|kya kar sakte|मदद|क्या कर सकते")),
    ("quit", re.compile(r"(?i)^(bas|band karo|bye|exit|quit|stop saathi)\b|^बस\b|बंद करो|^अलविदा")),
]
# Short utterances only: "tez bolo" is a control, "Mummy ko bolo jaldi aao" is a task.
_MAX_CONTROL_WORDS = 4

_NAME_PATTERNS = [
    re.compile(r"([^\s,.:।]+)\s+(?:ko|को)(?=\s|$)", re.IGNORECASE),
    re.compile(r"([^\s,.:।]+)\s+(?:ka|ki|ke|का|की|के)(?=\s)", re.IGNORECASE),
]
_NOT_NAMES = {
    "whatsapp",
    "blinkit",
    "screen",
    "message",
    "aur",
    "उस",
    "इस",
    "किस",
    "kis",
    "us",
    "is",
}
_REPLY = re.compile(r"(?i)^(reply|jawab|uttar|likho|bolo)\b|reply kar|जवाब|उत्तर|^बोलो|^लिखो")
_READ_MORE = re.compile(
    r"(?i)\b(aur|pichl[ae]|purane|pehle)\b.*\b(message|sandesh|msg)|पिछले|और संदेश"
)

HELP_TEXT = (
    "मैं WhatsApp पर संदेश पढ़ और जवाब भेज सकता हूँ, बता सकता हूँ किसके नए संदेश आए हैं, "
    "Blinkit से सामान कार्ट में डाल सकता हूँ, और बता सकता हूँ स्क्रीन पर क्या है। "
    "काम के बीच रोकने के लिए Enter दबाइए।"
)


def control(text: str) -> str | None:
    t = text.strip()
    if not t or len(t.split()) > _MAX_CONTROL_WORDS:
        return None
    return next((name for name, rx in _CONTROLS if rx.search(t)), None)


def contact_in(command: str) -> str | None:
    for rx in _NAME_PATTERNS:
        for m in rx.finditer(command):
            name = m.group(1)
            if name.lower() not in _NOT_NAMES:
                return name
    return None


@dataclass
class Session:
    last_answer: str = ""
    contact: str | None = None  # last person the user talked about
    pace: float = 1.0
    history: list[str] = field(default_factory=list)

    def remember(self, command: str, answer: str) -> None:
        self.last_answer = answer or self.last_answer
        self.contact = contact_in(command) or self.contact
        self.history.append(command)

    def expand(self, command: str) -> str:
        """Add the remembered contact to a follow-up that doesn't name anyone."""
        if not self.contact or contact_in(command):
            return command
        if _REPLY.search(command):
            return f"{self.contact} ko {command}"
        if _READ_MORE.search(command):
            return f"WhatsApp par {self.contact} ke pichle message padho"
        return command

    def adjust_pace(self, faster: bool) -> float:
        self.pace = round(min(1.6, self.pace + 0.2) if faster else max(0.6, self.pace - 0.2), 1)
        return self.pace
