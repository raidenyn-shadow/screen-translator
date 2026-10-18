import unittest

from bump_version import bump_version


class BumpVersionTest(unittest.TestCase):
    def test_minor_resets_patch_and_increments_android_code(self):
        updated, version = bump_version('versionCode = 21\nversionName = "0.8.2"\n', 'minor')
        self.assertEqual(version, '0.9.0')
        self.assertEqual(updated, 'versionCode = 22\nversionName = "0.9.0"\n')

    def test_major_resets_minor_and_patch_even_before_one(self):
        updated, version = bump_version('versionCode = 21\nversionName = "0.8.2"\n', 'major')
        self.assertEqual(version, '1.0.0')
        self.assertIn('versionCode = 22', updated)

    def test_rejects_unknown_increment_and_leading_zero_versions(self):
        with self.assertRaises(ValueError):
            bump_version('versionCode = 21\nversionName = "0.8.2"\n', 'automatic')
        with self.assertRaises(ValueError):
            bump_version('versionCode = 21\nversionName = "01.8.2"\n')

    def test_increments_patch_and_android_code(self):
        source = 'versionCode = 20\nversionName = "0.8.9"\n'
        updated, version = bump_version(source)
        self.assertEqual(version, "0.8.10")
        self.assertEqual(updated, 'versionCode = 21\nversionName = "0.8.10"\n')

    def test_preserves_other_build_configuration(self):
        source = 'android {\n    versionCode = 99\n    versionName = "1.2.3"\n}\n'
        updated, _ = bump_version(source)
        self.assertEqual(updated, source.replace('99', '100').replace('1.2.3', '1.2.4'))

    def test_rejects_missing_ambiguous_or_non_semantic_versions(self):
        for source in (
            'versionCode = 20\n',
            'versionName = "0.8.1"\n',
            'versionCode = 20\nversionName = "0.8.1-beta"\n',
            'versionCode = 20\nversionCode = 21\nversionName = "0.8.1"\n',
            'versionCode = 20\nversionName = "0.8.1"\nversionName = "0.8.2"\n',
        ):
            with self.subTest(source=source), self.assertRaises(ValueError):
                bump_version(source)


if __name__ == "__main__":
    unittest.main()
