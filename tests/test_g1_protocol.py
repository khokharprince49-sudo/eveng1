import unittest
from g1_protocol import build_text_packets, MAX_CHUNK_BYTES


class G1ProtocolTests(unittest.TestCase):
    def test_basic_packet_header(self):
        packets = build_text_packets('Hello G1')
        self.assertEqual(len(packets), 1)
        packet = packets[0]
        self.assertEqual(packet[0], 0x4E)
        self.assertEqual(packet[1], 0)
        self.assertEqual(packet[2], 1)
        self.assertEqual(packet[3], 0)
        self.assertEqual(packet[4], 0x40)
        self.assertEqual(packet[7], 1)
        self.assertEqual(packet[8], 1)
        self.assertIn(b'Hello G1', packet[9:])

    def test_long_text_pages(self):
        packets = build_text_packets('hello ' * 100)
        self.assertGreater(len(packets), 1)
        self.assertEqual(packets[-1][4], 0x40)
        self.assertTrue(all(p[4] == 0x30 for p in packets[:-1]))

    def test_unicode_chunks_preserve_utf8(self):
        packets = build_text_packets(('🙂' * 100) + ' café')
        self.assertTrue(all(len(p) <= 9 + MAX_CHUNK_BYTES for p in packets))
        # Each payload chunk is independently valid UTF-8.
        for packet in packets:
            packet[9:].decode('utf-8')

    def test_empty_is_not_supported_by_builder(self):
        packets = build_text_packets('')
        self.assertTrue(packets)


if __name__ == '__main__':
    unittest.main()
