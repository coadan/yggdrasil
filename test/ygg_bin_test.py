import os
import pathlib
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BIN_PATH = ROOT / "bin" / "ygg"


class YggBinTest(unittest.TestCase):
    def test_help_is_local_and_does_not_require_server(self):
        env = os.environ.copy()
        env["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "0"

        for arg in ["--help", "-h", "help"]:
            with self.subTest(arg=arg):
                result = subprocess.run(
                    [str(BIN_PATH), arg],
                    cwd=str(ROOT),
                    env=env,
                    text=True,
                    capture_output=True,
                    timeout=5,
                )

                self.assertEqual(0, result.returncode)
                self.assertIn("Usage: ygg start | ygg <command> ...", result.stdout)
                self.assertEqual("", result.stderr)

    def test_no_args_prints_local_usage(self):
        result = subprocess.run(
            [str(BIN_PATH)],
            cwd=str(ROOT),
            text=True,
            capture_output=True,
            timeout=5,
        )

        self.assertEqual(0, result.returncode)
        self.assertIn("Usage: ygg start | ygg <command> ...", result.stdout)
        self.assertEqual("", result.stderr)


if __name__ == "__main__":
    unittest.main()
