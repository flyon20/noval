from __future__ import annotations

import unittest

from app.utils.confuse_font_decoder import ConfuseFontDecoder


class TestableConfuseFontDecoder(ConfuseFontDecoder):
    def __init__(self) -> None:
        super().__init__()
        self.row_results: dict[tuple[str, ...], str | None] = {}
        self.single_results: dict[str, str] = {}

    def _ensure_font_path(self, css: str) -> tuple[str, str]:
        return "dc027189e0ba4cd", "fake-font.otf"

    def _recognize_batch(self, batch: list[str], font_path: str) -> str | None:
        return self.row_results.get(tuple(batch))

    def _recognize_single(self, char: str, font_path: str) -> str:
        return self.single_results.get(char, "")


class ConfuseFontDecoderTest(unittest.TestCase):

    def test_should_decode_batch_and_fallback_single(self) -> None:
        decoder = TestableConfuseFontDecoder()
        chars = [chr(int(code, 16)) for code in ["e3e9", "e3ea", "e3ee", "e3ef", "e3f2", "e418"]]
        decoder.row_results[(chars[0], chars[1], chars[2], chars[3], chars[4])] = "在主然表要"
        decoder.single_results[chars[5]] = "当"

        decoded = decoder.decode("".join(chars), "@font-face{font-family:test;}")

        self.assertEqual("在主然表要当", decoded)

    def test_should_apply_known_overrides_when_single_recognition_missing(self) -> None:
        decoder = TestableConfuseFontDecoder()
        decoded = decoder.decode(chr(int("e418", 16)), "@font-face{font-family:test;}")
        self.assertEqual("当", decoded)
