import asyncio
import datetime
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from backend.app.core.config import Settings
from backend.app.core.crypto import SecretCipher
from backend.app.core.prompts import get_yui_system_prompt
from backend.app.core.security import create_session_value, session_is_valid, token_is_valid
from backend.app.services.proactive_service import ConnectionManager, proactive_service


class SettingsAndSecurityTests(unittest.TestCase):
    def test_release_debug_value_is_parsed_as_false(self):
        settings = Settings(DEBUG="release", _env_file=None)
        self.assertFalse(settings.DEBUG)

    def test_tokens_and_sessions_are_validated(self):
        settings = Settings(API_TOKEN="a-long-test-token", _env_file=None)
        self.assertTrue(token_is_valid("a-long-test-token", settings))
        self.assertFalse(token_is_valid("wrong", settings))
        self.assertTrue(session_is_valid(create_session_value(settings), settings))

    def test_secret_cipher_round_trip(self):
        settings = Settings(DATA_ENCRYPTION_KEY="unit-test-encryption-key", _env_file=None)
        with patch("backend.app.core.crypto.get_settings", return_value=settings):
            cipher = SecretCipher()
        encrypted = cipher.encrypt("secret")
        self.assertTrue(encrypted.startswith("enc:v1:"))
        self.assertEqual(cipher.decrypt(encrypted), "secret")


class ReminderTests(unittest.TestCase):
    def test_naive_datetime_uses_named_timezone(self):
        timestamp = proactive_service.parse_due_timestamp(
            "2026-09-18 12:30", "America/Mexico_City"
        )
        expected = datetime.datetime(
            2026, 9, 18, 18, 30, tzinfo=datetime.timezone.utc
        ).timestamp()
        self.assertEqual(timestamp, expected)

    def test_iso_datetime_preserves_offset(self):
        timestamp = proactive_service.parse_due_timestamp("2026-09-18T12:30:00-06:00")
        expected = datetime.datetime(
            2026, 9, 18, 18, 30, tzinfo=datetime.timezone.utc
        ).timestamp()
        self.assertEqual(timestamp, expected)

    def test_invalid_datetime_is_rejected_without_crashing(self):
        self.assertIsNone(
            proactive_service.parse_due_timestamp("2026-99-40 27:75", "America/Mexico_City")
        )

    def test_broadcast_reports_successful_deliveries(self):
        class FakeSocket:
            async def send_json(self, _data):
                return None

        manager = ConnectionManager()
        manager.active_connections.add(FakeSocket())
        delivered = asyncio.run(manager.broadcast_json({"type": "test"}))
        self.assertEqual(delivered, 1)


class HttpAndPromptTests(unittest.TestCase):
    def test_private_api_requires_authentication(self):
        import backend.main as main

        old_token, old_debug = main.settings.API_TOKEN, main.settings.DEBUG
        main.settings.API_TOKEN, main.settings.DEBUG = "unit-test-token", False
        try:
            client = TestClient(main.app)
            self.assertEqual(client.get("/api/not-a-route").status_code, 401)
            self.assertEqual(
                client.post("/api/auth/login", json={"token": "wrong"}).status_code,
                401,
            )
            self.assertEqual(
                client.post("/api/auth/login", json={"token": "unit-test-token"}).status_code,
                200,
            )
            self.assertEqual(client.get("/api/not-a-route").status_code, 404)
            self.assertEqual(
                client.post(
                    "/api/reminders",
                    json={"title": "Inválido", "due_datetime": "2026-99-40 27:75"},
                ).status_code,
                422,
            )
        finally:
            main.settings.API_TOKEN, main.settings.DEBUG = old_token, old_debug

    def test_prompt_does_not_claim_unimplemented_capabilities(self):
        prompt = get_yui_system_prompt()
        self.assertIn("No puede contestar", prompt)
        self.assertIn("no existe verificación biométrica", prompt)
        self.assertIn("no implica que Gmail", prompt)


if __name__ == "__main__":
    unittest.main()
