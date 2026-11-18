"""按 3x3 分块抓取湘潭 bbox 内的 highway 要素（含节点 id 与几何），本地缓存。

输出：cache/ways_r{i}c{j}.json
"""
import json, os, sys, time, urllib.parse, urllib.request, ssl

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
UA = 'xtbus-walknav-build/1.0'
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'cache')
os.makedirs(CACHE, exist_ok=True)

BBOX = (27.70, 112.78, 28.02, 113.10)  # south, west, north, east（湘潭城区 + 湘潭县城）
ROWS, COLS = 2, 2

HIGHWAY_RE = ('^(motorway_link|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|'
              'tertiary_link|unclassified|residential|living_street|service|pedestrian|footway|path|'
              'steps|cycleway|track|road)$')

HOSTS = ('https://overpass-api.de/api/interpreter',
         'https://overpass.kumi.systems/api/interpreter',
         'https://overpass.private.coffee/api/interpreter')


def query_tile(s, w, n, e):
    return ('[out:json][timeout:300];'
            'way["highway"~"%s"](%f,%f,%f,%f);'
            'out body geom;' % (HIGHWAY_RE, s, w, n, e))


def post(q):
    data = urllib.parse.urlencode({'data': q}).encode()
    last = None
    for h in HOSTS:
        try:
            req = urllib.request.Request(h, data=data, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=400, context=ctx) as r:
                return json.loads(r.read().decode('utf-8', 'replace'))
        except Exception as ex:
            last = ex
            print('   host fail %s: %s' % (h, repr(ex)[:110]), flush=True)
            time.sleep(3)
    raise last


def main():
    s0, w0, n0, e0 = BBOX
    dh = (n0 - s0) / ROWS
    dw = (e0 - w0) / COLS
    total = 0
    for i in range(ROWS):
        for j in range(COLS):
            path = os.path.join(CACHE, 'ways_r%dc%d.json' % (i, j))
            s, n = s0 + i * dh, s0 + (i + 1) * dh
            w, e = w0 + j * dw, w0 + (j + 1) * dw
            if os.path.exists(path) and os.path.getsize(path) > 100:
                d = json.load(open(path, encoding='utf-8'))
                print('skip r%dc%d (%d ways, %.1f MB)' % (i, j, len(d['elements']),
                                                          os.path.getsize(path) / 1e6))
                total += len(d['elements'])
                continue
            print('fetch r%dc%d  %.4f,%.4f,%.4f,%.4f' % (i, j, s, w, n, e), flush=True)
            t = time.time()
            d = post(query_tile(s, w, n, e))
            json.dump(d, open(path, 'w', encoding='utf-8'), ensure_ascii=False)
            print('  -> %d ways, %.1f MB, %.0fs' % (len(d['elements']),
                                                    os.path.getsize(path) / 1e6, time.time() - t),
                  flush=True)
            total += len(d['elements'])
            time.sleep(4)
    print('total ways:', total)


if __name__ == '__main__':
    main()
