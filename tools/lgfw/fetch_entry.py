# fetch_entry.py <drive_id> <entry> <out> : stream one zip entry to a sparse file
import sys, zipfile, time
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from httpfile import HttpRangeFile, drive_url
fid, entry, out = sys.argv[1:4]
f = HttpRangeFile(drive_url(fid), block=16 << 20, cache_blocks=3)
z = zipfile.ZipFile(f)
zi = z.getinfo(entry)
zero = bytes(1 << 20)
t0 = time.time(); done = 0
with z.open(entry) as src, open(out, 'wb') as dst:
    while True:
        b = src.read(1 << 20)
        if not b:
            break
        if b == zero[:len(b)]:
            dst.seek(len(b), 1)
        else:
            dst.write(b)
        done += len(b)
        if done % (256 << 20) < (1 << 20):
            print(f"{done >> 20}/{zi.file_size >> 20} MB raw, {f.fetched >> 20} MB fetched, {time.time() - t0:.0f}s", flush=True)
    dst.truncate()
print("DONE", out, done, "bytes in", int(time.time() - t0), "s", flush=True)
