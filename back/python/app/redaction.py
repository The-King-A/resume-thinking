from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class Replacement:
    kind: str
    start: int
    end: int
    replacement: str


@dataclass(frozen=True)
class RedactionResult:
    redacted_text: str
    replacements: tuple[Replacement, ...]


_PATTERNS = (
    ("email", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"), "[REDACTED_EMAIL]"),
    ("identity_number", re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)"), "[REDACTED_ID]"),
    ("phone", re.compile(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)"), "[REDACTED_PHONE]"),
    ("address", re.compile(r"(?<!\S)(?:北京|上海|天津|重庆|广东|浙江|江苏|四川|湖北|湖南|山东|福建|安徽|河北|河南|陕西|辽宁|吉林|黑龙江|江西|广西|云南|贵州|山西|甘肃|海南|新疆|西藏|内蒙古|宁夏|青海)省?(?:[^\n,，。;；]{0,40})(?:路|街|道|号|室|区)(?!\S)"), "[REDACTED_ADDRESS]"),
)


def redact_text(text: str) -> RedactionResult:
    matches: list[tuple[int, int, str, str]] = []
    for kind, pattern, replacement in _PATTERNS:
        matches.extend((m.start(), m.end(), kind, replacement) for m in pattern.finditer(text))
    selected: list[tuple[int, int, str, str]] = []
    for item in sorted(matches, key=lambda x: (x[0], -(x[1] - x[0]))):
        if selected and item[0] < selected[-1][1]:
            continue
        selected.append(item)
    out: list[str] = []
    cursor = 0
    replacements: list[Replacement] = []
    for start, end, kind, replacement in selected:
        out.append(text[cursor:start]); out.append(replacement)
        replacements.append(Replacement(kind, start, end, replacement))
        cursor = end
    out.append(text[cursor:])
    return RedactionResult("".join(out), tuple(replacements))
