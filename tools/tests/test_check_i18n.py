import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_i18n", ROOT / "tools/check_i18n.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class GuardTests(unittest.TestCase):
    def test_multiline_text(self):
        self.assertEqual(1, len(guard.scan('Text(\n    "Not translated",\n    color = red,\n)')))

    def test_custom_text_components(self):
        for name in ("TacticalText", "StatusPill", "LabelValue"):
            with self.subTest(name=name):
                self.assertTrue(guard.scan(f'{name}("English text", value)'))

    def test_conditional_description(self):
        self.assertEqual(2, len(guard.scan('Icon(contentDescription = if (shown) "Hide password" else "Reveal password")')))

    def test_multiline_named_argument(self):
        self.assertTrue(guard.scan('Icon(\ncontentDescription =\n"Show password"\n)'))

    def test_mixed_translated_and_literal(self):
        self.assertTrue(guard.scan('Text(stringResource(R.string.title) + " untranslated suffix")'))

    def test_comments_are_not_ui(self):
        self.assertFalse(guard.scan('// Text("Comment")\n/* Text("Block comment") */'))

    def test_resources_and_dynamic_values(self):
        self.assertFalse(guard.scan('Text(stringResource(R.string.title, count))\nText(userName)'))

    def test_punctuation_and_numeric_formats(self):
        self.assertFalse(guard.scan('Text("—")\nText("%.2f".format(value))'))

    def test_interpolated_prose_is_found(self):
        self.assertTrue(guard.scan('Text("Profile $index")'))

    def test_technical_map_style_is_not_ui(self):
        self.assertFalse(guard.scan('val label = "#ffffff"\nreturn """{ "label": "map style" }"""'))

    def test_untranslated_text_after_other_resource_call(self):
        self.assertTrue(guard.scan('Text(stringResource(R.string.first))\nText(\n"Still English"\n)'))


class ResourceTests(unittest.TestCase):
    def test_locales_have_unique_matching_keys_and_format_arguments(self):
        locales = []
        for directory in ("values", "values-de"):
            path = ROOT / "android/app/src/main/res" / directory / "strings.xml"
            entries = [node for node in ET.parse(path).getroot() if node.tag == "string"]
            keys = [node.attrib["name"] for node in entries]
            self.assertEqual(len(keys), len(set(keys)), f"duplicate keys in {directory}")
            locales.append({node.attrib["name"]: ''.join(node.itertext()) for node in entries})
        english, german = locales
        self.assertEqual(english.keys(), german.keys())
        for key, text in english.items():
            with self.subTest(key=key):
                self.assertTrue(german[key].strip())
                self.assertEqual(sorted(re.findall(r'%\d+\$[a-zA-Z]', text)),
                                 sorted(re.findall(r'%\d+\$[a-zA-Z]', german[key])))


if __name__ == "__main__":
    unittest.main()
