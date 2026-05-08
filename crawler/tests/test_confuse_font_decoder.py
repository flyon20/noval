from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

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


class BundledMappingOnlyDecoder(ConfuseFontDecoder):
    def __init__(self, cache_dir: str | None = None) -> None:
        super().__init__(cache_dir=cache_dir)
        self.batch_calls = 0
        self.single_calls = 0

    def _ensure_font_path(self, css: str) -> tuple[str, str]:
        return "dc027189e0ba4cd", "fake-font.otf"

    def _recognize_batch(self, batch: list[str], font_path: str) -> str | None:
        self.batch_calls += 1
        return None

    def _recognize_single(self, char: str, font_path: str) -> str:
        self.single_calls += 1
        return ""


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
        chars = [chr(int(code, 16)) for code in ["e610", "e611", "e612", "e613"]]
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

    def test_should_apply_known_overrides_for_common_fanqie_chapter_typos(self) -> None:
        decoder = TestableConfuseFontDecoder()
        chars = [chr(int(code, 16)) for code in ["e4e2", "e513", "e511", "e510", "e479", "e424"]]

        decoded = decoder.decode("".join(chars), "@font-face{font-family:test;}")

        self.assertEqual("光望再口第爱", decoded)

    def test_should_apply_extended_known_overrides_for_real_world_chapter_phrases(self) -> None:
        decoder = TestableConfuseFontDecoder()
        chars = [chr(int(code, 16)) for code in ["e44b", "e503", "e485", "e4b0", "e512", "e551", "e552", "e559", "e48c"]]

        decoded = decoder.decode("".join(chars), "@font-face{font-family:test;}")

        self.assertEqual("少属位或妈两数海马", decoded)

    def test_should_prefer_known_overrides_over_cached_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cache_file = Path(temp_dir) / "dc027189e0ba4cd.mapping.json"
            cache_file.write_text(
                json.dumps({
                    chr(int("e4e2", 16)): "至",
                    chr(int("e513", 16)): "文",
                    chr(int("e511", 16)): "试",
                }, ensure_ascii=False),
                encoding="utf-8",
            )
            decoder = TestableConfuseFontDecoder(cache_dir=temp_dir)

            decoded = decoder.decode(
                "".join(chr(int(code, 16)) for code in ["e4e2", "e513", "e511"]),
                "@font-face{font-family:test;}",
            )

            self.assertEqual("光望再", decoded)

    def test_should_use_bundled_mapping_before_ocr_when_cache_is_empty(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            decoder = BundledMappingOnlyDecoder(cache_dir=temp_dir)

            decoded = decoder.decode(
                "\ue3e9\ue3ea\ue3eb\ue3ec",
                "@font-face{font-family:test;}",
            )

        self.assertEqual("\u5728\u4e3b\u7279\u5bb6", decoded)
        self.assertEqual(0, decoder.batch_calls)
        self.assertEqual(0, decoder.single_calls)

    def test_should_apply_manual_ascii_overrides_for_remaining_fanqie_glyphs(self) -> None:
        decoder = TestableConfuseFontDecoder()

        decoded = decoder.decode(
            "\ue3fa\ue408\ue448\ue45f\ue4a3",
            "@font-face{font-family:test;}",
        )

        self.assertEqual("golIj", decoded)

    def test_font_url_pattern_accepts_browser_font_face_css(self) -> None:
        css = '@font-face { font-family: TestFont; src: url("https://example.test/font.woff2") format("woff2"); }'

        self.assertIsNotNone(ConfuseFontDecoder.FONT_URL_PATTERN.search(css))
