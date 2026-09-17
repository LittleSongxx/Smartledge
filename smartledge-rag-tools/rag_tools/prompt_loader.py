from pathlib import Path


_PROMPT_CACHE: dict[str, str] = {}


def load_prompt(name: str) -> str:
    if name in _PROMPT_CACHE:
        return _PROMPT_CACHE[name]
    prompt_path = Path.cwd() / "prompt" / name
    if not prompt_path.exists():
        prompt_path = Path(__file__).resolve().parent.parent / "prompt" / name
    text = prompt_path.read_text(encoding="utf-8")
    _PROMPT_CACHE[name] = text.strip()
    return _PROMPT_CACHE[name]


def render_prompt(name: str, values: dict[str, object]) -> str:
    text = load_prompt(name)
    for key, value in values.items():
        text = text.replace("{{" + key + "}}", "" if value is None else str(value))
    return text
