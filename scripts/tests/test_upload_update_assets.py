import importlib.util
import io
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "_upload_update_assets.py"


def load_module():
    spec = importlib.util.spec_from_file_location("upload_update_assets", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeChannel:
    def __init__(self, code=0):
        self.code = code

    def recv_exit_status(self):
        return self.code


class FakeStream(io.BytesIO):
    def __init__(self, text="", code=0):
        super().__init__(text.encode("utf-8"))
        self.channel = FakeChannel(code)


class FakeClient:
    def __init__(self, find_output):
        self.find_output = find_output
        self.commands = []

    def exec_command(self, cmd, timeout=None, get_pty=False):
        self.commands.append(cmd)
        if "mc find" in cmd:
            return None, FakeStream(self.find_output), FakeStream("")
        return None, FakeStream(""), FakeStream("")


class UpdateAssetRetentionTest(unittest.TestCase):
    def test_stale_update_objects_keeps_latest_three_versions_and_latest_manifest(self):
        module = load_module()
        objects = [
            "updates/latest.yml",
            "updates/LianYu-Setup-0.2.258.exe",
            "updates/LianYu-Setup-0.2.258.exe.blockmap",
            "updates/LianYu-Setup-0.2.259.exe",
            "updates/LianYu-Setup-0.2.259.exe.blockmap",
            "updates/LianYu-Setup-0.2.260.exe",
            "updates/LianYu-Setup-0.2.260.exe.blockmap",
            "updates/LianYu-Setup-0.2.261.exe",
            "updates/LianYu-Setup-0.2.261.exe.blockmap",
            "updates/readme.txt",
        ]

        self.assertEqual(
            module.stale_update_objects(objects, keep=3),
            [
                "updates/LianYu-Setup-0.2.258.exe",
                "updates/LianYu-Setup-0.2.258.exe.blockmap",
            ],
        )

    def test_stale_update_objects_handles_multi_digit_versions_semantically(self):
        module = load_module()
        objects = [
            "updates/LianYu-Setup-0.2.9.exe",
            "updates/LianYu-Setup-0.2.10.exe",
            "updates/LianYu-Setup-0.2.11.exe",
            "updates/LianYu-Setup-0.2.12.exe",
        ]

        self.assertEqual(
            module.stale_update_objects(objects, keep=3),
            ["updates/LianYu-Setup-0.2.9.exe"],
        )

    def test_cleanup_old_update_assets_removes_only_stale_install_assets(self):
        module = load_module()
        client = FakeClient(
            "\n".join(
                [
                    "local/lianyu/updates/latest.yml",
                    "local/lianyu/updates/LianYu-Setup-0.2.258.exe",
                    "local/lianyu/updates/LianYu-Setup-0.2.258.exe.blockmap",
                    "local/lianyu/updates/LianYu-Setup-0.2.259.exe",
                    "local/lianyu/updates/LianYu-Setup-0.2.260.exe",
                    "local/lianyu/updates/LianYu-Setup-0.2.261.exe",
                    "local/lianyu/updates/readme.txt",
                ]
            )
        )

        module.cleanup_old_update_assets(client, keep=3)

        remove_commands = [cmd for cmd in client.commands if "mc rm" in cmd]
        self.assertEqual(len(remove_commands), 2)
        self.assertIn("local/lianyu/updates/LianYu-Setup-0.2.258.exe", remove_commands[0])
        self.assertIn("local/lianyu/updates/LianYu-Setup-0.2.258.exe.blockmap", remove_commands[1])
        self.assertFalse(any("latest.yml" in cmd for cmd in remove_commands))
        self.assertFalse(any("readme.txt" in cmd for cmd in remove_commands))


if __name__ == "__main__":
    unittest.main()
