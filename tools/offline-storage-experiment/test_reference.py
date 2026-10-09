import hashlib,json,struct,unittest
from reference import generate,expected,validate,load,PHASES

class ReferenceControls(unittest.TestCase):
    def test_golden_record_and_offsets(self):
        data=generate(8,7,23)
        self.assertEqual(struct.unpack("<QqIIQ",data[64:96]),(1,514717,1,0,2))
        self.assertEqual(expected(data)["records"],8)
    def test_hash_binds_header_and_record(self):
        a=expected(generate(8,7,23));b=expected(generate(8,8,23));c=expected(generate(8,7,24))
        self.assertNotEqual(a["source_sha256"],b["source_sha256"]);self.assertEqual(a["index_sha256"],b["index_sha256"]);self.assertNotEqual(a["index_sha256"],c["index_sha256"])
    def test_duplicates_truncation_trailing_bad_schema(self):
        data=generate(8);duplicate=bytearray(data);duplicate[96:104]=duplicate[64:72]
        for bad in (data[:-1],data+b"x",b"BADMAGIC"+data[8:],bytes(duplicate)):
            with self.assertRaises(ValueError):expected(bad)
    def test_result_binding_and_no_coercion(self):
        reference=expected(generate(8));row=dict(schema="OFFLINE_STORAGE_SAMPLE_V1",session="a"*32,challenge="b"*64,mode="mmap",production_authority=False,sidecar_bytes=4096,timings_ns={p:0 for p in PHASES},**reference)
        self.assertEqual(validate(json.dumps(row),reference,"mmap","a"*32,"b"*64),row)
        for field,value in (("session","c"*32),("challenge","c"*64),("schema","WRONG"),("source_sha256","f"*64),("epoch",8),("records",True),("production_authority",0)):
            wrong=dict(row);wrong[field]=value
            with self.assertRaises(ValueError):validate(json.dumps(wrong),reference,"mmap","a"*32,"b"*64)
        with self.assertRaises(ValueError):validate(json.dumps(row)+"\n"+json.dumps(row),reference,"mmap","a"*32,"b"*64)
    def test_nonfinite_duplicate_and_large_generation(self):
        for text in ('{"x":1,"x":2}','{"x":NaN}'):
            with self.assertRaises(ValueError):load(text)
        with self.assertRaises(ValueError):generate(262144)

if __name__=="__main__":unittest.main()
