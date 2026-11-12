# Reads an OpenFst binary header (fst/fst.h FstHeader::Read) and reports embedded symbol tables.
import struct, sys
f = open(sys.argv[1], 'rb')
def i32(): return struct.unpack('<i', f.read(4))[0]
def i64(): return struct.unpack('<q', f.read(8))[0]
def s(): n = i32(); return f.read(n).decode('utf-8', 'replace')
magic = i32(); fsttype = s(); arctype = s(); version = i32(); flags = i32(); props = struct.unpack('<Q', f.read(8))[0]
start = i64(); nstates = i64(); narcs = i64()
print(dict(magic=hex(magic), fsttype=fsttype, arctype=arctype, version=version, flags=flags, start=start, nstates=nstates, narcs=narcs, has_isyms=bool(flags & 1), has_osyms=bool(flags & 2)))
