"""Minimal G1 text packet builder based on public community protocol notes.

This code builds text packets only; Bluetooth transport is handled by the Android
bridge. Protocol support varies by G1 firmware and has not been hardware-tested here.
"""
from __future__ import annotations
import textwrap

DISPLAY_COLUMNS = 20
LINES_PER_PAGE = 5
MAX_CHUNK_BYTES = 176
HEADER_LEN = 9


def _utf8_chunks(text: str, limit: int = MAX_CHUNK_BYTES) -> list[bytes]:
    """Split by UTF-8 code point so a multibyte character is never cut in half."""
    chunks: list[bytes] = []
    current = bytearray()
    for char in text:
        encoded = char.encode('utf-8')
        if current and len(current) + len(encoded) > limit:
            chunks.append(bytes(current))
            current.clear()
        current.extend(encoded)
    if current or not chunks:
        chunks.append(bytes(current))
    return chunks


def _wrap_text(text: str, width: int = DISPLAY_COLUMNS) -> list[str]:
    text = text.replace('⬆', '^').replace('⟶', '-')
    lines: list[str] = []
    for paragraph in text.splitlines() or ['']:
        if not paragraph:
            lines.append('')
            continue
        wrapped = textwrap.wrap(
            paragraph, width=width, break_long_words=True,
            break_on_hyphens=False, replace_whitespace=True, drop_whitespace=True,
        )
        lines.extend(wrapped or [''])
    return lines or ['']


def build_text_packets(text: str) -> list[bytes]:
    """Create 0x4E text packets, ordered by page, for both lenses.

    The packet header is: command, sequence, chunk-count, chunk-index,
    screen-status, character-position-low, character-position-high, page, pages.
    Payload is UTF-8. Text is arranged into ~20-character lines, five per page.
    """
    lines = _wrap_text(text)
    pages = [lines[n:n + LINES_PER_PAGE] for n in range(0, len(lines), LINES_PER_PAGE)]
    packets: list[bytes] = []
    seq = 0
    for page_index, page_lines in enumerate(pages, start=1):
        padded = page_lines + [''] * (LINES_PER_PAGE - len(page_lines))
        page_text = '\n'.join(padded)
        chunks = _utf8_chunks(page_text)
        for chunk_index, chunk in enumerate(chunks):
            # During multi-page display the last page uses display-complete (0x40).
            # Earlier pages use displaying | new-content (0x30).
            status = 0x40 if page_index == len(pages) else 0x30
            header = bytes((0x4E, seq & 0xFF, len(chunks) & 0xFF,
                            chunk_index & 0xFF, status, 0, 0,
                            page_index & 0xFF, len(pages) & 0xFF))
            packets.append(header + chunk)
        seq = (seq + 1) & 0xFF
    return packets


def packet_preview(text: str) -> dict:
    packets = build_text_packets(text)
    return {'pages_or_chunks': len(packets), 'lengths': [len(p) for p in packets]}
