import io
import json
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dana_gateway import ALLOWED, clean_text, is_negated, sanitize_response, infer, GatewayHandler


class GatewayTests(unittest.TestCase):
    def test_negated_imperatives(self):
        for phrase in ("دانا چراغ رو روشن نکن", "به رضا زنگ نزن", "نمیخوام دوربین رو باز کنی"):
            self.assertTrue(is_negated(phrase), phrase)
        self.assertFalse(is_negated("به رضا بگو من فردا نمیام"))
        self.assertFalse(is_negated("به رضا بگو چراغ رو روشن نکن"))

    def test_valid_output(self):
        self.assertEqual(sanitize_response({"action": "call", "target": "رضا"}),
                         {"action": "CALL", "target": "رضا"})
        self.assertEqual(sanitize_response({"action": "SMS_DRAFT", "target": "رضا", "message": "نمیام"})["message"], "نمیام")
        for action in ("FLASH_ON", "FLASH_OFF", "TIME", "BATTERY"):
            self.assertIn(action, ALLOWED)
            self.assertEqual(sanitize_response({"action": action})["action"], action)

    def test_reject_invalid(self):
        for payload in ({"action": "DELETE_FILES"}, {"action": "CALL", "target": ""},
                        {"action": "CALL", "target": "رضا\n0921"},
                        {"action": "SMS_DRAFT", "target": "رضا", "message": "x" * 451},
                        {"action": "OPEN_APP", "target": "x" * 81},
                        {"needs_clarification": True}, []):
            self.assertEqual(sanitize_response(payload), {"needs_clarification": True})

    def test_expanded_voice_actions(self):
        self.assertEqual(sanitize_response({"action": "MUSIC_RANDOM"}), {"action": "MUSIC_RANDOM"})
        self.assertEqual(sanitize_response({"action": "MUSIC_SEARCH", "target": "شادمهر"}),
                         {"action": "MUSIC_SEARCH", "target": "شادمهر"})
        self.assertEqual(sanitize_response({"action": "MEDIA_NEXT"}), {"action": "MEDIA_NEXT"})
        self.assertEqual(sanitize_response({"action": "CHAT_ANSWER", "answer": "پاریس"}),
                         {"action": "CHAT_ANSWER", "answer": "پاریس"})
        self.assertEqual(sanitize_response({"action": "WIFI_ON"}), {"action": "WIFI_ON"})
        self.assertEqual(sanitize_response({"action": "SET_TIMER", "minutes": 5}),
                         {"action": "SET_TIMER", "minutes": 5})
        self.assertEqual(sanitize_response({"action": "SET_ALARM", "hour": 8, "minute": 30,
                                            "day_offset": 1}),
                         {"action": "SET_ALARM", "hour": 8, "minute": 30, "day_offset": 1})

    def test_reject_unsafe_action_arguments(self):
        for item in ({"action": "SET_TIMER", "minutes": -1},
                     {"action": "SET_TIMER", "minutes": "5"},
                     {"action": "SET_ALARM", "hour": 25, "minute": 0},
                     {"action": "SET_ALARM", "hour": 8, "minute": 0, "day_offset": 99},
                     {"action": "MUSIC_SEARCH", "target": "hi\nbye"},
                     {"action": "CHAT_ANSWER", "answer": "x" * 451},
                     {"action": "DELETE_FILES"}):
            self.assertEqual(sanitize_response(item), {"needs_clarification": True})

    def test_input_size(self):
        self.assertIsNone(clean_text("x" * 501, 500))
        self.assertIsNone(clean_text("\n", 500))
        self.assertEqual(clean_text(" رضا ", 80), "رضا")

    @mock.patch("dana_gateway.request.urlopen")
    def test_model_call_uses_server_key_and_validation(self, mocked):
        content = '{"action":"CALL","target":"رضا"}'
        response = io.BytesIO(json.dumps({"choices": [{"message": {"content": content}}]}).encode())
        response.__enter__ = lambda self: self
        response.__exit__ = lambda self, *args: None
        mocked.return_value = response
        actual = infer("با رضا تماس بگیر", "server-only-key", "gpt-4o-mini")
        self.assertEqual(actual, {"action": "CALL", "target": "رضا"})
        req = mocked.call_args[0][0]
        self.assertTrue(req.get_header("Authorization").endswith("server-only-key"))
        self.assertNotIn(b"server-only-key", req.data)

    @mock.patch("dana_gateway.request.urlopen")
    def test_negative_request_never_reaches_model(self, mocked):
        self.assertEqual(infer("زنگ نزن", "key", "model"), {"needs_clarification": True})
        mocked.assert_not_called()


if __name__ == "__main__":
    unittest.main()
