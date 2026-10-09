import unittest
from reference import Reference,record,query,brute_pairs,grid_pairs,wrap

class ReferenceChecks(unittest.TestCase):
    def test_integer_wrap(self):self.assertEqual(wrap((1<<63)-1+1),-(1<<63))
    def test_known_tick(self):
        r=Reference();r.apply("SPAWN 0 0");b=r.apply("TICK");self.assertEqual(b["rows"][0]["position"],[-1,-1,-1]);self.assertEqual(b["events"],[[9,0,1]])
    def test_stale_and_cross_world(self):
        r=Reference();r.apply("SPAWN 0 0");r.apply("DESPAWN 9 0 1");r.apply("SPAWN 0 1");self.assertEqual(r.apply("PROBE 9 0 1")["status"],"STALE");self.assertEqual(r.apply("PROBE 8 0 2")["status"],"STALE")
    def test_spatial_independent_grid_vs_brute(self):
        rows={i:record(i,i//3) for i in range(60)};self.assertEqual(grid_pairs(rows),[(tuple(a),tuple(b)) for a,b in brute_pairs(rows)])
    def test_touching(self):
        rows={0:record(0,0),1:record(1,0)};rows[1]["position"]=[2,0,0];self.assertEqual(brute_pairs(rows),[[[9,0,1],[9,1,1]]]);self.assertEqual(query(rows,[-1]*3,[0]*3),[[9,0,1]])
    def test_cold_survives_churn(self):
        r=Reference();r.apply("SPAWN 0 4");before=r.apply("INITIAL")["rows"][0];r.apply("EXT 9 0 1 99");r.apply("EXT 9 0 1 -1");after=r.apply("INITIAL")["rows"][0];self.assertEqual(before,after)
    def test_invalid_lifecycle_resolves_identity_first(self):
        r=Reference();r.apply("SPAWN 0 1");r.apply("DESPAWN 9 0 1");r.apply("SPAWN 0 2")
        self.assertEqual(r.apply("LIFE 9 0 1 2")["status"],"STALE")
        self.assertEqual(r.apply("LIFE 9 0 2 2")["status"],"LIMIT")
        self.assertEqual(r.apply("INITIAL")["rows"][0]["lifecycle"],1)

if __name__=="__main__":unittest.main()
