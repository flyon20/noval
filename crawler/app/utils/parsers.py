from __future__ import annotations

import json
from typing import Any

from bs4 import BeautifulSoup


def extract_initial_state(html: str) -> dict[str, Any]:
    marker = "window.__INITIAL_STATE__="
    marker_index = html.find(marker)
    if marker_index < 0:
        raise ValueError("initial state not found")

    json_start = html.find("{", marker_index)
    if json_start < 0:
        raise ValueError("initial state json start not found")

    depth = 0
    in_string = False
    escaped = False
    quote_char = ""

    for index in range(json_start, len(html)):
        char = html[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote_char:
                in_string = False
            continue

        if char in ('"', "'"):
            in_string = True
            quote_char = char
            continue

        if char == "{":
            depth += 1
            continue

        if char == "}":
            depth -= 1
            if depth == 0:
                return json.loads(_normalize_js_literals(html[json_start:index + 1]))

    raise ValueError("initial state json end not found")


def _normalize_js_literals(payload: str) -> str:
    result: list[str] = []
    in_string = False
    escaped = False
    quote_char = ""
    index = 0

    while index < len(payload):
        char = payload[index]
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote_char:
                in_string = False
            index += 1
            continue

        if char in ('"', "'"):
            in_string = True
            quote_char = char
            result.append(char)
            index += 1
            continue

        if payload.startswith("undefined", index):
            result.append("null")
            index += len("undefined")
            continue

        result.append(char)
        index += 1

    return "".join(result)


def html_to_text(html: str) -> str:
    if not html:
        return ""
    soup = BeautifulSoup(html, "lxml")
    paragraphs = [item.get_text(strip=True) for item in soup.find_all("p")]
    if paragraphs:
        return "\n".join(paragraphs)
    return soup.get_text("\n", strip=True)
