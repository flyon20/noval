from __future__ import annotations

import tempfile
import unittest

from app.utils.confuse_font_decoder import ConfuseFontDecoder


class TestableConfuseFontDecoder(ConfuseFontDecoder):
    def __init__(self, cache_dir: str | None = None) -> None:
        super().__init__(cache_dir=cache_dir)
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

    def test_should_accept_ascii_letters_and_digits_from_ocr(self) -> None:
        decoder = TestableConfuseFontDecoder()
        for ch in "BOSS123":
            self.assertTrue(decoder._is_acceptable_char(ch))

    def test_should_fallback_to_single_when_batch_contains_ascii_letters(self) -> None:
        decoder = TestableConfuseFontDecoder()
        chars = [chr(int(code, 16)) for code in ["e510", "e511", "e512", "e513"]]
        decoder.row_results[tuple(chars)] = "测B中文"
        decoder.single_results = {
            chars[0]: "测",
            chars[1]: "试",
            chars[2]: "中",
            chars[3]: "文",
        }

        decoded = decoder.decode("".join(chars), "@font-face{font-family:test;}")

        self.assertEqual("试中文", decoded[1:])
        self.assertEqual("测试中文", decoded)

    def test_should_persist_mapping_to_disk_and_reuse_across_instances(self) -> None:
        chars = [chr(int(code, 16)) for code in ["e600", "e601", "e602", "e603"]]
        with tempfile.TemporaryDirectory() as temp_dir:
            first = TestableConfuseFontDecoder(cache_dir=temp_dir)
            first.row_results[tuple(chars)] = "性能优化"
            decoded_first = first.decode("".join(chars), "@font-face{font-family:test;}")
            self.assertEqual("性能优化", decoded_first)

            second = TestableConfuseFontDecoder(cache_dir=temp_dir)
            decoded_second = second.decode("".join(chars), "@font-face{font-family:test;}")
            self.assertEqual("性能优化", decoded_second)
