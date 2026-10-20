import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ohi import (
    ArtifactSelection, Format, Lock, LockedFile, LockError, LockException,
    decode_lock, encode_lock, read_lock, write_lock,
)
from test_publication import EXAMPLES


def example():
    return json.loads((EXAMPLES / "selection.lock.json").read_text(encoding="utf-8"))


class LockAssertions(unittest.TestCase):
    def assert_error(self, error, code, operation, path=None):
        self.assertIsInstance(error.error, LockError)
        data = json.loads(error.error.model_dump_json())
        self.assertEqual(data["code"], code)
        self.assertEqual(data["operation"], operation)
        self.assertTrue(data["message"].strip())
        if path is None:
            self.assertEqual(set(data), {"code", "operation", "message"})
        else:
            self.assertEqual(set(data), {"code", "operation", "message", "path"})
            self.assertEqual(data["path"], os.fspath(path))

    def assert_example_wire(self, data):
        self.assertEqual(set(data), {"format_version", "selections", "files"})
        self.assertEqual(data["format_version"], 1)
        self.assertEqual(data["selections"], [{
            "base_url": "https://Hub.example/releases/z/2/?v=%2F#chosen",
            "url": "https://Files.example/Z%2F.ttl?download=1#file",
        }])
        self.assertEqual(data["files"], [
            {"base_url": "https://Hub.example/releases/z/2/?v=%2F#chosen",
             "url": "https://Files.example/Z%2F.ttl?download=1#file", "format": "owl-turtle"},
            {"base_url": "https://Hub.example/releases/a/1/",
             "url": "https://files.example/a.yaml", "format": "linkml-yaml"},
        ])


class LockCodecTests(LockAssertions):
    def reject(self, data, code="INVALID_LOCK"):
        with self.assertRaises(LockException) as caught:
            decode_lock(json.dumps(data))
        self.assert_error(caught.exception, code, "DECODE")

    def test_decodes_exact_direct_and_dependency_records_in_supplied_order(self):
        lock = decode_lock((EXAMPLES / "selection.lock.json").read_text(encoding="utf-8"))
        self.assertIsInstance(lock, Lock)
        self.assertIsInstance(lock.selections[0], ArtifactSelection)
        self.assertIsInstance(lock.files[0], LockedFile)
        self.assert_example_wire(lock.model_dump(mode="json"))

    def test_encodes_exact_fields_and_order_with_public_publication_format(self):
        lock = Lock.model_validate(example())
        lock.files[0].format = Format.owl_turtle
        self.assert_example_wire(json.loads(encode_lock(lock)))

    def test_accepts_integral_revision_and_preserves_required_empty_arrays(self):
        lock = decode_lock('{"format_version": 1.0, "selections": [], "files": []}')
        self.assertIs(type(lock.format_version), int)
        self.assertEqual(lock.selections, [])
        self.assertEqual(lock.files, [])
        self.assertEqual(json.loads(encode_lock(lock)),
                         {"format_version": 1, "selections": [], "files": []})

    def test_rejects_malformed_json_and_leading_bom(self):
        for text in ("{", "\ufeff" + json.dumps(example())):
            with self.subTest(text=text):
                with self.assertRaises(LockException) as caught:
                    decode_lock(text)
                self.assert_error(caught.exception, "INVALID_LOCK", "DECODE")
                self.assertIsInstance(caught.exception.__cause__, json.JSONDecodeError)

    def test_rejects_nonobject_root(self):
        for data in ([], None):
            with self.subTest(data=data):
                self.reject(data)

    def test_rejects_missing_or_null_required_collections(self):
        for field in ("selections", "files"):
            for missing in (True, False):
                with self.subTest(field=field, missing=missing):
                    data = example()
                    if missing:
                        del data[field]
                    else:
                        data[field] = None
                    self.reject(data)

    def test_rejects_unknown_properties_at_each_record_level(self):
        for scope in ("root", "selections", "files"):
            with self.subTest(scope=scope):
                data = example()
                target = data if scope == "root" else data[scope][0]
                target["future"] = True
                self.reject(data)

    def test_rejects_unknown_format(self):
        data = example()
        data["files"][0]["format"] = "owl"
        self.reject(data)

    def test_rejects_invalid_file_uris(self):
        for field in ("base_url", "url"):
            with self.subTest(field=field):
                data = example()
                data["files"][1][field] = "relative.ttl"
                self.reject(data)

    def test_reports_unsupported_integer_revision_before_missing_fields(self):
        self.reject({"format_version": 2.0}, "UNSUPPORTED_VERSION")

    def test_rejects_missing_or_noninteger_revision(self):
        data = {"selections": [], "files": []}
        self.reject(data)
        for value in (True, 1.5, "1"):
            with self.subTest(value=value):
                self.reject({**data, "format_version": value})

    def test_rejects_duplicate_selection_pair(self):
        data = example()
        data["selections"].append(dict(data["selections"][0]))
        self.reject(data)

    def test_rejects_duplicate_file_pair(self):
        data = example()
        data["files"].append(dict(data["files"][0]))
        self.reject(data)

    def test_rejects_conflicting_formats_for_one_file_pair(self):
        data = example()
        data["files"].append({**data["files"][0], "format": "rdfs-turtle"})
        self.reject(data)

    def test_requires_file_matching_both_selection_fields(self):
        for field in ("base_url", "url"):
            with self.subTest(field=field):
                data = example()
                data["files"][0][field] = "urn:other"
                self.reject(data)

    def test_preserves_distinct_exact_pairs_and_selection_order(self):
        cases = {
            "same file": [("urn:z", "urn:file"), ("urn:a", "urn:file")],
            "same publication": [("urn:pub", "urn:z"), ("urn:pub", "urn:a")],
            "URI spelling": [("https://Example.org/pub", "https://example.org/f%2F"),
                             ("https://example.org/pub", "https://example.org/f%2f")],
        }
        for name, pairs in cases.items():
            with self.subTest(name=name):
                data = {"format_version": 1,
                        "selections": [{"base_url": base, "url": url} for base, url in pairs],
                        "files": [{"base_url": base, "url": url, "format": "owl-turtle"}
                                  for base, url in pairs]}
                lock = decode_lock(json.dumps(data))
                self.assertEqual([(item.base_url, item.url) for item in lock.selections], pairs)
                self.assertEqual([(item.base_url, item.url) for item in lock.files], pairs)
                wire = json.loads(encode_lock(lock))
                self.assertEqual([(item["base_url"], item["url"]) for item in wire["selections"]], pairs)
                self.assertEqual([(item["base_url"], item["url"]) for item in wire["files"]], pairs)

    def test_encode_rejects_mutated_pairs_and_unvalidated_uri(self):
        for fault in ("duplicate", "missing selected file", "invalid URI"):
            with self.subTest(fault=fault):
                lock = Lock.model_validate(example())
                if fault == "duplicate":
                    lock.files.append(lock.files[0])
                elif fault == "missing selected file":
                    lock.files.pop(0)
                else:
                    lock.files[1].url = "relative.yaml"
                with self.assertRaises(LockException) as caught:
                    encode_lock(lock)
                self.assert_error(caught.exception, "INVALID_LOCK", "ENCODE")
                self.assertIsNotNone(caught.exception.__cause__)

    def test_encode_classifies_unsupported_constructed_revision(self):
        lock = Lock.model_construct(format_version=2, selections=[], files=[])
        with self.assertRaises(LockException) as caught:
            encode_lock(lock)
        self.assert_error(caught.exception, "UNSUPPORTED_VERSION", "ENCODE")


class LockFileTests(LockAssertions):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.path = self.directory / "chosen.lock.json"
        self.lock = Lock.model_validate(example())

    def assert_entries(self, *names):
        self.assertEqual(sorted(entry.name for entry in self.directory.iterdir()), sorted(names))

    def fail_staging(self, write_error=None, close_error=None):
        def create(*args, **kwargs):
            staged = tempfile.NamedTemporaryFile(*args, **kwargs)
            self.assertEqual(Path(staged.name).parent, self.directory)
            if write_error is not None:
                write = staged.write

                def fail_write(content):
                    write(content[:5])
                    raise write_error

                staged.write = fail_write
            if close_error is not None:
                close = staged.close

                def fail_close():
                    close()
                    raise close_error

                staged.close = fail_close
            return staged

        return create

    def assert_failed_write_preserves_destination(self, failure):
        with self.assertRaises(LockException) as caught:
            write_lock(self.path, self.lock)
        self.assert_error(caught.exception, "STORAGE_ERROR", "WRITE", self.path)
        self.assertIs(caught.exception.__cause__, failure)
        self.assertEqual(self.path.read_bytes(), b"previous bytes")
        self.assert_entries(self.path.name)

    def test_creates_complete_utf8_json_without_bom(self):
        write_lock(self.path, self.lock)
        content = self.path.read_bytes()
        self.assertFalse(content.startswith(b"\xef\xbb\xbf"))
        self.assert_example_wire(json.loads(content.decode("utf-8")))
        self.assert_entries(self.path.name)

    def test_replaces_invalid_existing_bytes_as_a_whole(self):
        self.path.write_bytes(b"old invalid bytes" * 100)
        write_lock(self.path, Lock(format_version=1, selections=[], files=[]))
        self.assertEqual(json.loads(self.path.read_bytes()),
                         {"format_version": 1, "selections": [], "files": []})
        self.assert_entries(self.path.name)

    def test_reads_explicit_utf8_file_without_changing_it(self):
        content = (EXAMPLES / "selection.lock.json").read_bytes()
        self.path.write_bytes(content)
        self.assert_example_wire(read_lock(self.path).model_dump(mode="json"))
        self.assertEqual(self.path.read_bytes(), content)
        self.assert_entries(self.path.name)

    def test_rejects_invalid_encodings_without_changing_file(self):
        text = '{"format_version": 1, "selections": [], "files": []}'
        for name, content in (("invalid UTF-8", b"\xff"),
                              ("BOM", b"\xef\xbb\xbf" + text.encode("utf-8")),
                              ("UTF-16", text.encode("utf-16"))):
            with self.subTest(encoding=name):
                self.path.write_bytes(content)
                with self.assertRaises(LockException) as caught:
                    read_lock(self.path)
                self.assert_error(caught.exception, "INVALID_LOCK", "READ", self.path)
                self.assertIsNotNone(caught.exception.__cause__)
                self.assertEqual(self.path.read_bytes(), content)
                self.assert_entries(self.path.name)

    def test_missing_read_reports_original_path_and_native_cause(self):
        supplied = str(self.directory) + "/./missing.lock.json"
        with self.assertRaises(LockException) as caught:
            read_lock(supplied)
        self.assert_error(caught.exception, "STORAGE_ERROR", "READ", supplied)
        self.assertIsInstance(caught.exception.__cause__, FileNotFoundError)
        self.assert_entries()

    def test_read_classifies_filename_encoding_failure_as_storage_error(self):
        cause = UnicodeEncodeError("utf-8", "\ud800", 0, 1, "surrogates not allowed")
        with patch("ohi.lock.Path.read_bytes", side_effect=cause):
            with self.assertRaises(LockException) as caught:
                read_lock(self.path)
        self.assert_error(caught.exception, "STORAGE_ERROR", "READ", self.path)
        self.assertIs(caught.exception.__cause__, cause)
        self.assert_entries()

    def test_read_preserves_public_operation_for_unsupported_revision(self):
        self.path.write_text('{"format_version": 2.0}', encoding="utf-8")
        with self.assertRaises(LockException) as caught:
            read_lock(self.path)
        self.assert_error(caught.exception, "UNSUPPORTED_VERSION", "READ", self.path)
        self.assertEqual(self.path.read_bytes(), b'{"format_version": 2.0}')
        self.assert_entries(self.path.name)

    def test_validation_precedes_staging_and_preserves_destination(self):
        self.path.write_bytes(b"previous bytes")
        self.lock.files.pop(0)
        supplied = str(self.directory) + "/./chosen.lock.json"
        with patch("ohi.lock.NamedTemporaryFile") as stage:
            with self.assertRaises(LockException) as caught:
                write_lock(supplied, self.lock)
            stage.assert_not_called()
        self.assert_error(caught.exception, "INVALID_LOCK", "WRITE", supplied)
        self.assertEqual(self.path.read_bytes(), b"previous bytes")
        self.assert_entries(self.path.name)

    def test_write_classifies_unsupported_revision_before_missing_parent(self):
        path = self.directory / "absent" / "lock.json"
        lock = Lock.model_construct(format_version=2, selections=[], files=[])
        with self.assertRaises(LockException) as caught:
            write_lock(path, lock)
        self.assert_error(caught.exception, "UNSUPPORTED_VERSION", "WRITE", path)
        self.assert_entries()

    def test_refuses_absent_parent_without_creating_directories(self):
        path = self.directory / "absent" / "lock.json"
        with self.assertRaises(LockException) as caught:
            write_lock(path, self.lock)
        self.assert_error(caught.exception, "STORAGE_ERROR", "WRITE", path)
        self.assertIsInstance(caught.exception.__cause__, FileNotFoundError)
        self.assert_entries()

    def test_staging_write_failure_preserves_bytes_and_removes_temporary_file(self):
        self.path.write_bytes(b"previous bytes")
        failure = OSError("staging write failed")
        with patch("ohi.lock.NamedTemporaryFile", side_effect=self.fail_staging(write_error=failure)):
            self.assert_failed_write_preserves_destination(failure)

    def test_staging_close_failure_preserves_bytes_and_removes_temporary_file(self):
        self.path.write_bytes(b"previous bytes")
        failure = OSError("staging close failed")
        with patch("ohi.lock.NamedTemporaryFile", side_effect=self.fail_staging(close_error=failure)):
            self.assert_failed_write_preserves_destination(failure)

    def test_write_failure_remains_primary_when_close_also_fails(self):
        self.path.write_bytes(b"previous bytes")
        failure = OSError("staging write failed")
        factory = self.fail_staging(write_error=failure, close_error=OSError("close failed"))
        with patch("ohi.lock.NamedTemporaryFile", side_effect=factory):
            self.assert_failed_write_preserves_destination(failure)

    def test_atomic_replacement_failure_preserves_bytes_without_fallback(self):
        self.path.write_bytes(b"previous bytes")
        failure = OSError("atomic replacement unsupported")
        with patch("ohi.lock.os.replace", side_effect=failure) as replace:
            self.assert_failed_write_preserves_destination(failure)
        replace.assert_called_once()
        self.assertEqual(Path(replace.call_args.args[0]).parent, self.directory)

    def test_cleanup_failure_reports_temporary_path_and_preserves_primary_cause(self):
        self.path.write_bytes(b"previous bytes")
        failure = OSError("atomic replacement unsupported")
        with patch("ohi.lock.os.replace", side_effect=failure), \
                patch("ohi.lock.os.unlink", side_effect=OSError("cleanup denied")):
            with self.assertRaises(LockException) as caught:
                write_lock(self.path, self.lock)
        self.assert_error(caught.exception, "STORAGE_ERROR", "WRITE", self.path)
        self.assertIs(caught.exception.__cause__, failure)
        self.assertEqual(self.path.read_bytes(), b"previous bytes")
        temporary = [entry for entry in self.directory.iterdir() if entry != self.path]
        self.assertEqual(len(temporary), 1)
        self.assertIn(str(temporary[0]), str(caught.exception))
        self.assert_example_wire(json.loads(temporary[0].read_bytes()))

    @unittest.skipUnless(hasattr(os, "symlink"), "host does not support symbolic links")
    def test_replaces_final_symlink_without_changing_its_referent(self):
        referent = self.directory / "referent.json"
        referent.write_bytes(b"referent bytes")
        self.path.symlink_to(referent)
        write_lock(self.path, self.lock)
        self.assertFalse(self.path.is_symlink())
        self.assertEqual(referent.read_bytes(), b"referent bytes")
        self.assert_example_wire(json.loads(self.path.read_bytes()))
        self.assert_entries(self.path.name, referent.name)


if __name__ == "__main__":
    unittest.main()
