import unittest

from fastapi.testclient import TestClient

from backend.app.core.config import Settings
from backend.app.core.prompts import get_yui_system_prompt
from backend.app.core.security import create_session_value, session_is_valid, token_is_valid


class SettingsAndSecurityTests(unittest.TestCase):
    def test_release_debug_value_is_parsed_as_false(self):
        settings = Settings(DEBUG="release", _env_file=None)
        self.assertFalse(settings.DEBUG)

    def test_tokens_and_sessions_are_validated(self):
        settings = Settings(API_TOKEN="a-long-test-token", _env_file=None)
        self.assertTrue(token_is_valid("a-long-test-token", settings))
        self.assertFalse(token_is_valid("wrong", settings))
        self.assertTrue(session_is_valid(create_session_value(settings), settings))


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
            self.assertEqual(client.post("/api/chat", json={"message": ""}).status_code, 422)
        finally:
            main.settings.API_TOKEN, main.settings.DEBUG = old_token, old_debug

    def test_only_chat_routes_are_exposed(self):
        import backend.main as main

        paths = set(main.app.openapi()["paths"])
        self.assertIn("/api/chat", paths)
        self.assertIn("/api/voice/speak", paths)
        for removed in ("/api/reminders", "/api/contacts", "/api/telephony/incoming",
                        "/api/google/accounts", "/api/ws/live"):
            self.assertNotIn(removed, paths)

    def test_prompt_does_not_claim_unimplemented_capabilities(self):
        prompt = get_yui_system_prompt()
        self.assertIn("No puede contestar", prompt)
        self.assertIn("No puedes crear recordatorios", prompt)


if __name__ == "__main__":
    unittest.main()
