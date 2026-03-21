from __future__ import annotations

import hashlib
import re
import tempfile
from pathlib import Path
from urllib.request import urlretrieve

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont, ImageOps
from rapidocr_onnxruntime import RapidOCR


class ConfuseFontDecoder:
    BATCH_SIZE = 10
    ROW_FONT_SIZE = 150
    SINGLE_FONT_SIZES = (220, 280, 340)
    CELL_SIZE = 180
    ROW_HEIGHT = 180
    SINGLE_CANVAS = 520
    FONT_URL_PATTERN = re.compile(r"src:url\((https://[^)]+?\.woff2)\)")
    KNOWN_OVERRIDES = {
        "dc027189e0ba4cd": {
            0xE3F3: "只",
            0xE3FC: "儿",
            0xE418: "当",
            0xE41C: "些",
            0xE41F: "十",
            0xE422: "气",
            0xE436: "了",
            0xE4DE: "一",
            0xE534: "己",
            0xE535: "老",
            0xE52F: "友",
            0xE542: "太",
            0xE547: "她",
            0xE54C: "子",
            0xE557: "样",
            0xE55A: "们",
        }
    }

    def __init__(self) -> None:
        self._ocr = RapidOCR()
        self._font_path_cache: dict[str, str] = {}
        self._mapping_cache: dict[str, dict[str, str]] = {}
        self._workdir = Path(tempfile.gettempdir()) / "novel-crawler-fonts"
        self._workdir.mkdir(parents=True, exist_ok=True)

    def decode(self, raw_text: str, css: str) -> str:
        obfuscated_chars = sorted({ch for ch in raw_text if 0xE000 <= ord(ch) <= 0xF8FF})
        if not obfuscated_chars:
            return raw_text

        font_signature, font_path = self._ensure_font_path(css)
        mapping = self._mapping_cache.setdefault(font_signature, {})
        missing = [ch for ch in obfuscated_chars if ch not in mapping]
        if missing:
            mapping.update(self._build_mapping(font_signature, missing, font_path))
        return "".join(mapping.get(ch, ch) for ch in raw_text)

    def _build_mapping(self, font_signature: str, chars: list[str], font_path: str) -> dict[str, str]:
        mapping: dict[str, str] = {}
        overrides = self.KNOWN_OVERRIDES.get(font_signature, {})
        unresolved = [ch for ch in chars if ord(ch) not in overrides]

        for codepoint, target in overrides.items():
            mapping[chr(codepoint)] = target

        for index in range(0, len(unresolved), self.BATCH_SIZE):
            batch = unresolved[index:index + self.BATCH_SIZE]
            text = self._recognize_batch(batch, font_path)
            if text and len(text) == len(batch):
                for source, target in zip(batch, text):
                    mapping[source] = target

        for ch in unresolved:
            if ch in mapping:
                continue
            target = self._recognize_single(ch, font_path)
            if target:
                mapping[ch] = target
        return mapping

    def _ensure_font_path(self, css: str) -> tuple[str, str]:
        match = self.FONT_URL_PATTERN.search(css or "")
        if not match:
            raise ValueError("font url not found from css")

        font_url = match.group(1)
        font_signature = Path(font_url).stem
        cached = self._font_path_cache.get(font_signature)
        if cached and Path(cached).exists():
            return font_signature, cached

        woff_path = self._workdir / f"{font_signature}.woff2"
        otf_path = self._workdir / f"{font_signature}.otf"
        if not woff_path.exists():
            urlretrieve(font_url, woff_path)
        if not otf_path.exists():
            font = TTFont(woff_path)
            font.flavor = None
            font.save(otf_path)
        self._font_path_cache[font_signature] = str(otf_path)
        return font_signature, str(otf_path)

    def _recognize_batch(self, batch: list[str], font_path: str) -> str | None:
        if not batch:
            return None
        font = ImageFont.truetype(font_path, self.ROW_FONT_SIZE)
        image = Image.new("L", (len(batch) * self.CELL_SIZE, self.ROW_HEIGHT), "white")
        draw = ImageDraw.Draw(image)
        for index, ch in enumerate(batch):
            origin_x = index * self.CELL_SIZE
            bbox = draw.textbbox((0, 0), ch, font=font)
            x = origin_x + (self.CELL_SIZE - (bbox[2] - bbox[0])) // 2 - bbox[0]
            y = (self.ROW_HEIGHT - (bbox[3] - bbox[1])) // 2 - bbox[1]
            draw.text((x, y), ch, font=font, fill="black")
        image = ImageOps.autocontrast(image)
        image_path = self._workdir / f"row_{hashlib.sha1(''.join(batch).encode()).hexdigest()}.png"
        image.save(image_path)
        result, _ = self._ocr(str(image_path))
        if not result:
            return None
        text = "".join(item[1] for item in result).strip()
        if len(text) != len(batch):
            return None
        if not all(self._is_acceptable_char(ch) for ch in text):
            return None
        return text

    def _recognize_single(self, char: str, font_path: str) -> str:
        best = ""
        best_confidence = -1.0
        for font_size in self.SINGLE_FONT_SIZES:
            font = ImageFont.truetype(font_path, font_size)
            image = Image.new("L", (self.SINGLE_CANVAS, self.SINGLE_CANVAS), "white")
            draw = ImageDraw.Draw(image)
            bbox = draw.textbbox((0, 0), char, font=font)
            x = (self.SINGLE_CANVAS - (bbox[2] - bbox[0])) // 2 - bbox[0]
            y = (self.SINGLE_CANVAS - (bbox[3] - bbox[1])) // 2 - bbox[1]
            draw.text((x, y), char, font=font, fill="black")
            image = ImageOps.autocontrast(image)
            image_path = self._workdir / f"single_{ord(char):x}_{font_size}.png"
            image.save(image_path)
            result, _ = self._ocr(str(image_path))
            if not result:
                continue
            text = result[0][1][:1]
            confidence = float(result[0][2])
            if self._is_acceptable_char(text) and confidence > best_confidence:
                best = text
                best_confidence = confidence
        return best

    def _is_acceptable_char(self, char: str) -> bool:
        if not char:
            return False
        code = ord(char)
        if 0x4E00 <= code <= 0x9FFF:
            return True
        if 0x3400 <= code <= 0x4DBF:
            return True
        if char in "，。！？；：、“”‘’（）《》〈〉【】[]—…,.!?;:'\"()-":
            return True
        return False
