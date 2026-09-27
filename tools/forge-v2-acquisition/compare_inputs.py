"""Independent structural diagnosis of two exact inputs; never admits an identity."""
import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/writer-placement-v2'))
from capture import capture, tool_inventory
from io_utils import sha, write


def differences(before, after, limit=32):
    """Preserve the first differences in canonical array order, with a hard bound."""
    found = []

    def visit(left, right, path):
        if len(found) >= limit:
            return
        if type(left) is not type(right):
            found.append(dict(path=path, before=left, after=right))
        elif isinstance(left, list):
            if len(left) != len(right):
                found.append(dict(path=path + ['length'], before=len(left), after=len(right)))
            for index, (a, b) in enumerate(zip(left, right)):
                visit(a, b, path + [index])
        elif left != right:
            found.append(dict(path=path, before=left, after=right))

    if type(limit) is not int or not 0 < limit <= 256:
        raise ValueError('difference bound')
    visit(before, after, [])
    return found


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pre', type=Path, required=True)
    p.add_argument('--input', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--classpath', required=True)
    p.add_argument('--rust-parser', type=Path, required=True)
    a = p.parse_args()
    out = a.output.resolve()
    if not out.is_relative_to(ROOT / 'target/forge-v2-acquisition'):
        raise ValueError('isolated target output required')
    out.mkdir(parents=True, exist_ok=False)
    pins = tool_inventory(str(a.java.resolve()), a.classpath, str(a.rust_parser.resolve()))
    pins[str(Path(__file__).resolve())] = sha(Path(__file__))
    for name in ['capture.py', 'classfile.py', 'io_utils.py']:
        file = ROOT / 'tools/writer-placement-v2' / name
        pins[str(file)] = sha(file)
    inputs = {}
    paths = []
    for label, path in [('pre', a.pre.resolve()), ('input', a.input.resolve())]:
        if not path.is_relative_to(ROOT / 'target') or not 0 < path.stat().st_size <= 16 << 20:
            raise ValueError('bounded isolated input required')
        inputs[str(path)] = sha(path)
        target = out / (label + '.bin')
        shutil.copyfile(path, target)
        if sha(target) != inputs[str(path)]:
            raise ValueError('input changed during copy')
        paths.append(target)
    rows = capture(paths, out / 'parser', str(a.java.resolve()), a.classpath,
                   str(a.rust_parser.resolve()), unique_names=False)
    left, right = rows['pre'], rows['input']
    if left['receipt'][1] != right['receipt'][1]:
        raise ValueError('different class names')
    result = dict(schema='EXACT_HOOK_INPUT_DIAGNOSIS_V1', production_authority=False,
                  admission_result=False, class_name=left['receipt'][1], inputs=inputs,
                  tools_and_sources=pins, pre_identity=left['receipt'], input_identity=right['receipt'],
                  semantic_equal=left['receipt'][2] == right['receipt'][2],
                  declaration_order_equal=left['receipt'][3] == right['receipt'][3],
                  independent_parser_agreements=2,
                  first_semantic_differences=differences(left['dump'], right['dump']))
    for path, digest in {**pins, **inputs}.items():
        if sha(Path(path)) != digest:
            raise ValueError('source/tool/input drift: ' + path)
    write(out / 'diagnosis.json', result)
    print(json.dumps({k: v for k, v in result.items() if k not in ['tools_and_sources', 'inputs']}))


if __name__ == '__main__':
    main()
