import urllib.request, io, os, time

class HttpRangeFile(io.RawIOBase):
    """Seekable read-only file over HTTP Range requests, with a block cache."""
    def __init__(self, url, block=1<<20, cache_blocks=256):
        self.url = url; self.pos = 0; self.block = block
        self.cache = {}; self.order = []; self.cache_blocks = cache_blocks
        r = self._req(0, 0)
        self.size = int(r.headers["Content-Range"].split("/")[-1])
        self.fetched = 0
    def _req(self, a, b):
        for attempt in range(6):
            try:
                req = urllib.request.Request(self.url, headers={"Range": f"bytes={a}-{b}"})
                return urllib.request.urlopen(req, timeout=120)
            except Exception as e:
                if attempt == 5: raise
                time.sleep(2 ** attempt)
    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos
    def seek(self, off, whence=0):
        if whence == 0: self.pos = off
        elif whence == 1: self.pos += off
        else: self.pos = self.size + off
        return self.pos
    def _block(self, i):
        if i in self.cache: return self.cache[i]
        a = i * self.block; b = min(self.size, a + self.block) - 1
        data = self._req(a, b).read()
        self.fetched += len(data)
        self.cache[i] = data; self.order.append(i)
        if len(self.order) > self.cache_blocks:
            old = self.order.pop(0); self.cache.pop(old, None)
        return data
    def read(self, n=-1):
        if n is None or n < 0: n = self.size - self.pos
        n = max(0, min(n, self.size - self.pos))
        out = bytearray()
        while n > 0:
            i = self.pos // self.block; off = self.pos % self.block
            blk = self._block(i)
            chunk = blk[off:off + n]
            if not chunk: break
            out += chunk; self.pos += len(chunk); n -= len(chunk)
        return bytes(out)
    def readinto(self, b):
        d = self.read(len(b)); b[:len(d)] = d; return len(d)

def drive_url(fid):
    return f"https://drive.usercontent.google.com/download?id={fid}&export=download&confirm=t"
