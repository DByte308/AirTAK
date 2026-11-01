import importlib.util
import json
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("package_firmwares", pathlib.Path(__file__).with_name("package_firmwares.py"))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class FirmwarePackageTests(unittest.TestCase):
    def test_esp_factory_matches_app_bootloader_and_partition_table(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            app = b"\xe9application"
            boot = b"\xe9bootloader"
            partitions = b"partitions"
            factory = bytearray(b"\xff" * 0x10000)
            factory[:len(boot)] = boot
            factory[0x8000:0x8000 + len(partitions)] = partitions
            for name, data in {"firmware.bin": app, "firmware.factory.bin": bytes(factory) + app,
                               "bootloader.bin": boot, "partitions.bin": partitions}.items():
                (root / name).write_bytes(data)
            self.assertEqual(["firmware.bin", "firmware.factory.bin"], package.validate_output(root, "esp32"))
            (root / "firmware.bin").write_bytes(b"\xe9stale application")
            with self.assertRaisesRegex(ValueError, "factory/app mismatch"):
                package.validate_output(root, "esp32")

    def test_nrf_zip_requires_manifest_and_nonempty_payloads(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            with zipfile.ZipFile(root / "firmware.zip", "w") as archive:
                archive.writestr("manifest.json", json.dumps({"manifest": {"application": {
                    "bin_file": "firmware.bin", "dat_file": "firmware.dat"}}}))
                archive.writestr("firmware.bin", b"application")
                archive.writestr("firmware.dat", b"metadata")
            self.assertEqual(["firmware.zip"], package.validate_output(root, "nrf52"))
            with zipfile.ZipFile(root / "firmware.zip", "w") as archive:
                archive.writestr("manifest.json", json.dumps({"manifest": {"application": {
                    "bin_file": "firmware.bin", "dat_file": "firmware.dat"}}}))
                archive.writestr("firmware.bin", b"")
                archive.writestr("firmware.dat", b"metadata")
            with self.assertRaisesRegex(ValueError, "empty DFU"):
                package.validate_output(root, "nrf52")

    def test_manifest_requires_every_production_board(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / "manifest.json").write_text('{"boards": {}}')
            with self.assertRaisesRegex(ValueError, "board coverage"):
                package.verify(root)

    def test_published_files_and_hashes_are_checked(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            boards = {}
            sums = []
            for env, (folder, family) in package.BOARDS.items():
                (root / folder).mkdir()
                files = []
                for name in (["firmware.bin", "firmware.factory.bin"] if family == "esp32" else ["firmware.zip"]):
                    path = f"{folder}/{name}"
                    data = env.encode()
                    (root / path).write_bytes(data)
                    checksum = package.digest(data)
                    files.append({"path": path, "sha256": checksum, "bytes": len(data)})
                    sums.append(f"{checksum}  {path}")
                boards[env] = {"directory": folder, "files": files}
            (root / "manifest.json").write_text(json.dumps({"boards": boards}))
            (root / "SHA256SUMS").write_text("\n".join(sorted(sums)) + "\n")
            package.verify(root)
            first = next(root.rglob("*.bin"))
            first.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                package.verify(root)


if __name__ == "__main__":
    unittest.main()
