import unittest
from types import SimpleNamespace

from ohi import ohi_release_page


class PagesTests(unittest.TestCase):
    def test_links_a_release_page_on_ohi_dev(self):
        release = SimpleNamespace(namespace="w3c", name="sosa", version="2017")
        self.assertEqual(ohi_release_page(release), "https://ohi.dev/o/w3c/sosa@2017")

    def test_encodes_each_part_of_the_release_page_path(self):
        release = SimpleNamespace(namespace="a b", name="x/y@z", version="1.0+amendment.1 é")
        self.assertEqual(ohi_release_page(release),
                         "https://ohi.dev/o/a%20b/x%2Fy%40z@1.0%2Bamendment.1%20%C3%A9")

    def test_leaves_the_same_characters_as_javascript(self):
        release = SimpleNamespace(namespace="-_.!~*'()", name="n", version="v")
        self.assertEqual(ohi_release_page(release), "https://ohi.dev/o/-_.!~*'()/n@v")


if __name__ == "__main__":
    unittest.main()
