import unittest
from compare_inputs import differences


class DifferenceControls(unittest.TestCase):
    def test_first_boundary_and_limit(self):
        self.assertEqual(differences([1, [2, 3], 4], [1, [5, 6], 7], 1),
                         [dict(path=[1, 0], before=2, after=5)])

    def test_types_not_coerced(self):
        self.assertEqual(differences([0], [False])[0]['path'], [0])

    def test_length_and_nested_boundary(self):
        self.assertEqual([r['path'] for r in differences([[1, 2]], [[3]])],
                         [[0, 'length'], [0, 0]])

    def test_same_and_bad_limits(self):
        self.assertEqual(differences([['a', None]], [['a', None]]), [])
        for value in [0, -1, 257, True]:
            with self.assertRaises(ValueError):
                differences([], [], value)


if __name__ == '__main__':
    unittest.main()
