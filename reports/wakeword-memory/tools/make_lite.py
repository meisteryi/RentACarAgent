# Builds the "lite G" variant: words.txt from Gr.fst's embedded output symbols + a 1-state vector FST as Gr.fst.
import struct, sys, shutil, os
src, dst = sys.argv[1], sys.argv[2]
f = open(os.path.join(src, 'graph', 'Gr.fst'), 'rb')
def i32(): return struct.unpack('<i', f.read(4))[0]
def i64(): return struct.unpack('<q', f.read(8))[0]
def s(): n = i32(); return f.read(n).decode('utf-8')
assert i32() == 2125659606; s(); s(); i32(); flags = i32(); f.read(8); i64(); i64(); i64()
def symtab():
    assert i32() == 2125658996, 'symbol table magic'
    s(); i64(); n = i64(); return [(s(), i64()) for _ in range(n)]
isyms = symtab() if flags & 1 else None
osyms = symtab() if flags & 2 else None
if os.path.exists(dst): shutil.rmtree(dst)
shutil.copytree(src, dst, ignore=shutil.ignore_patterns('Gr.fst'))
with open(os.path.join(dst, 'graph', 'words.txt'), 'w', encoding='utf-8', newline='\n') as w:
    for sym, key in osyms: w.write(f'{sym} {key}\n')
def ws(b, x): b.write(struct.pack('<i', len(x))); b.write(x.encode())
with open(os.path.join(dst, 'graph', 'Gr.fst'), 'wb') as g:   # OpenFst VectorFst v2, std arc: 1 final state, 0 arcs
    g.write(struct.pack('<i', 2125659606)); ws(g, 'vector'); ws(g, 'standard')
    g.write(struct.pack('<iiQ', 2, 0, 0)); g.write(struct.pack('<qqq', 0, 1, 0))
    g.write(struct.pack('<fq', 0.0, 0))
print('words', len(osyms), 'isyms_equal_osyms', isyms == osyms, 'sample', osyms[:3], [w for w in osyms if w[0] in ('렌디', '<UNK>', '[unk]', '<unk>')])
