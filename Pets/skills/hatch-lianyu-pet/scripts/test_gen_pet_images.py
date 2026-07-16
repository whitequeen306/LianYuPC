import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT_PATH = Path(__file__).with_name("gen_pet_images.py")
SPEC = importlib.util.spec_from_file_location("gen_pet_images", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class RunImageEditRetryTest(unittest.TestCase):
    def test_retries_when_proxy_returns_transient_502_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            prompt_dir = Path(tmp) / "prompts"
            prompt_dir.mkdir(parents=True)
            prompt_file = prompt_dir / "base-pet.md"
            prompt_file.write_text("prompt", encoding="utf-8")

            image_path = Path(tmp) / "reference.png"
            image_path.write_bytes(b"png")

            response_path = Path(tmp) / "raw" / "base-pet.response.json"
            writes = iter([
                "error code: 502",
                json.dumps({"data": [{"b64_json": "aGVsbG8="}]}, ensure_ascii=False),
            ])

            def fake_run(*args, **kwargs):
                response_path.parent.mkdir(parents=True, exist_ok=True)
                response_path.write_text(next(writes), encoding="utf-8")

                class Result:
                    returncode = 0
                    stderr = ""

                return Result()

            with patch.object(MODULE.subprocess, "run", side_effect=fake_run), patch.object(MODULE.time, "sleep"):
                response = MODULE.run_image_edit(
                    base_url="https://example.test/v1",
                    api_key="test-key",
                    model="gpt-image-2",
                    prompt_file=prompt_file,
                    image_paths=[image_path],
                )

            self.assertEqual("aGVsbG8=", response["data"][0]["b64_json"])


if __name__ == "__main__":
    unittest.main()
