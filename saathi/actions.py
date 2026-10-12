"""Pydantic action schema: tap, type, scroll, back, home, open_app, look, ask_user, done, fail.

The model replies in a flat JSON shape (MODEL_SCHEMA, strict structured output) and
parse_decision() turns it into a validated Decision. Invalid -> ValueError (caller retries once).
"""

from typing import Annotated, Literal

from pydantic import BaseModel, Field, TypeAdapter, ValidationError


class Tap(BaseModel):
    type: Literal["tap"] = "tap"
    index: int = Field(ge=0)


class TypeText(BaseModel):
    type: Literal["type"] = "type"
    text: str = Field(min_length=1, max_length=500)
    index: int | None = Field(default=None, ge=0)


class Scroll(BaseModel):
    type: Literal["scroll"] = "scroll"
    direction: Literal["up", "down"]


class Search(BaseModel):
    """App product search via a deep link (Blinkit): lands directly on the results."""

    type: Literal["search"] = "search"
    query: str = Field(min_length=1, max_length=80)


class Back(BaseModel):
    type: Literal["back"] = "back"


class Home(BaseModel):
    type: Literal["home"] = "home"


class OpenApp(BaseModel):
    type: Literal["open_app"] = "open_app"
    app: str = Field(min_length=1)


class Look(BaseModel):
    type: Literal["look"] = "look"


class AskUser(BaseModel):
    type: Literal["ask_user"] = "ask_user"
    question: str = Field(min_length=1)


class Done(BaseModel):
    type: Literal["done"] = "done"
    summary: str = Field(min_length=1)


class Fail(BaseModel):
    type: Literal["fail"] = "fail"
    reason: str = Field(min_length=1)


Action = Annotated[
    Tap | TypeText | Scroll | Search | Back | Home | OpenApp | Look | AskUser | Done | Fail,
    Field(discriminator="type"),
]
_action_adapter: TypeAdapter[Action] = TypeAdapter(Action)


class Decision(BaseModel):
    thought: str = ""
    say: str = ""
    action: Action


ACTION_TYPES = [
    "tap",
    "type",
    "scroll",
    "search",
    "back",
    "home",
    "open_app",
    "look",
    "ask_user",
    "done",
    "fail",
]

# Strict JSON schema sent to the model. Every field is required (strict mode); unused ones are null.
MODEL_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["thought", "say", "action"],
    "properties": {
        "thought": {"type": "string"},
        "say": {"type": "string"},
        "action": {
            "type": "object",
            "additionalProperties": False,
            "required": ["type", "index", "text", "direction", "app"],
            "properties": {
                "type": {"type": "string", "enum": ACTION_TYPES},
                "index": {"type": ["integer", "null"]},
                "text": {
                    "type": ["string", "null"],
                    "description": (
                        "type: text to enter; search: product query; ask_user: question; "
                        "done: summary; fail: reason"
                    ),
                },
                "direction": {"type": ["string", "null"], "enum": ["up", "down", None]},
                "app": {"type": ["string", "null"]},
            },
        },
    },
}


def parse_decision(raw: dict) -> Decision:
    """Map the flat model output onto the typed action. Raises ValueError if invalid."""
    try:
        a = dict(raw["action"])
        kind = a.get("type")
        text = a.get("text")
        fields: dict = {"type": kind}
        if kind == "tap":
            fields["index"] = a.get("index")
        elif kind == "type":
            fields |= {"text": text, "index": a.get("index")}
        elif kind == "scroll":
            fields["direction"] = a.get("direction")
        elif kind == "search":
            fields["query"] = text
        elif kind == "open_app":
            fields["app"] = a.get("app") or text
        elif kind == "ask_user":
            fields["question"] = text
        elif kind == "done":
            fields["summary"] = text
        elif kind == "fail":
            fields["reason"] = text
        action = _action_adapter.validate_python(fields)
        return Decision(thought=raw.get("thought") or "", say=raw.get("say") or "", action=action)
    except (KeyError, TypeError, ValidationError) as e:
        raise ValueError(f"invalid model output: {e}") from e
