"""校验紧凑图：吸附距离统计 + A* 连通性与耗时。"""
import heapq, json, math, os, struct, statistics, time

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(HERE, 'cache', 'walk_xt.bin')


class Graph:
    def __init__(self, path):
        b = open(path, 'rb').read()
        self.b = b
        magic, ver, flags, n, m, g, k, date, eoff, goff, noff, nblen = struct.unpack_from('<4sHHIIIIIIIII', b, 0)
        assert magic == b'XTWG', magic
        self.n, self.m, self.g, self.k = n, m, g, k
        self.date = date
        o = 44
        # 节点区是「结构体数组」：lon,lat 交替
        nn = struct.unpack_from('<%di' % (n * 2), b, o)
        self.nx = nn[0::2]
        self.ny = nn[1::2]
        o += n * 8
        rec = struct.unpack_from('<%di' % (m * 5), b, o)
        self.froms = rec[0::5]
        self.tos = rec[1::5]
        self.lens = rec[2::5]
        self.goffs = rec[3::5]
        self.nameIdx = struct.unpack_from('<%dh' % m, b, o + m * 20 - 10) if False else None

        # 逐条解析 u16 nameIdx + u8 flags + u8 geomCount
        self.names = []
        self.flags = []
        self.gc = []
        p = o
        for i in range(m):
            p += 16
            ni = struct.unpack_from('<h', b, p)[0]; p += 2
            fl, gc = struct.unpack_from('<BB', b, p); p += 2
            self.names.append(ni)
            self.flags.append(fl)
            self.gc.append(gc)
        self.geom = struct.unpack_from('<%dh' % (g * 2), b, goff)
        self.nameOff = struct.unpack_from('<%dI' % k, b, noff)
        nbase = noff + k * 4
        self.nameStr = []
        for i in range(k):
            s = self.nameOff[i]
            e = self.nameOff[i + 1] if i + 1 < k else nblen
            self.nameStr.append(b[nbase + s:nbase + e].decode('utf-8', 'replace'))

        # CSR 无向
        deg = [0] * (n + 1)
        for i in range(m):
            deg[self.froms[i]] += 1
            deg[self.tos[i]] += 1
        self.start = [0] * (n + 1)
        s = 0
        for i in range(n):
            self.start[i] = s
            s += deg[i]
        self.start[n] = s
        self.adj = [0] * s
        self.adjEdge = [0] * s
        fill = self.start[:]
        for i in range(m):
            a, b2 = self.froms[i], self.tos[i]
            self.adj[fill[a]] = b2; self.adjEdge[fill[a]] = i; fill[a] += 1
            self.adj[fill[b2]] = a; self.adjEdge[fill[b2]] = i; fill[b2] += 1

    def ll(self, i):
        return self.ny[i] / 1e7, self.nx[i] / 1e7

    def poly_len(self, e):
        """按几何增量累加还原折线并算长度，用于校验编码一致性。"""
        lat, lon = self.ll(self.froms[e])
        acc = 0.0
        base = self.goffs[e]
        for k in range(self.gc[e]):
            nlat = lat + self.geom[(base + k) * 2 + 1] / 1e5
            nlon = lon + self.geom[(base + k) * 2] / 1e5
            acc += hav(lat, lon, nlat, nlon)
            lat, lon = nlat, nlon
        tlat, tlon = self.ll(self.tos[e])
        return acc + hav(lat, lon, tlat, tlon)


def hav(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1; dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def seg_dist(plat, plon, alat, alon, blat, blon):
    """点到线段近似距离（米）+ 投影比例 t。"""
    ky = 111320.0
    kx = 111320.0 * math.cos(math.radians(plat))
    ax, ay = (alon - plon) * kx, (alat - plat) * ky
    bx, by = (blon - plon) * kx, (blat - plat) * ky
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
    px, py = ax + t * dx, ay + t * dy
    return math.hypot(px, py), t


class Router:
    def __init__(self, g):
        self.g = g
        self.speed = [1.25, 1.35, 1.30, 1.30, 1.25, 1.20, 0.55, 1.10]  # class 0..7
        self.penalty = [0, 0, 0, 0, 0, 0, 0, 0]

    def snap(self, lat, lon, max_m=300):
        g = self.g
        best = (1e9, -1, 0.0)
        for i in range(g.m):
            a, b = g.froms[i], g.tos[i]
            alat, alon = g.ll(a)
            blat, blon = g.ll(b)
            d, t = seg_dist(lat, lon, alat, alon, blat, blon)
            if d < best[0]:
                best = (d, i, t)
        return best

    def astar(self, slat, slon, dlat, dlon):
        g = self.g
        sn = min(range(g.n), key=lambda i: hav(slat, slon, *g.ll(i)))
        dn = min(range(g.n), key=lambda i: hav(dlat, dlon, *g.ll(i)))
        dlat0, dlon0 = g.ll(dn)
        INF = float('inf')
        dist = [INF] * g.n
        prevE = [-1] * g.n
        dist[sn] = 0.0
        pq = [(hav(*g.ll(sn), dlat0, dlon0), 0.0, sn)]
        visited = 0
        while pq:
            f, dcur, u = heapq.heappop(pq)
            if dcur > dist[u]:
                continue
            visited += 1
            if u == dn:
                break
            ulat, ulon = g.ll(u)
            for k in range(g.start[u], g.start[u + 1]):
                v = g.adj[k]
                e = g.adjEdge[k]
                L = g.lens[e] / 100.0
                cls = g.flags[e] & 7
                c = L / self.speed[cls] + self.penalty[cls]
                nd = dcur + c
                if nd < dist[v] - 1e-9:
                    dist[v] = nd
                    prevE[v] = e
                    vl, vo = g.ll(v)
                    heapq.heappush(pq, (nd + hav(vl, vo, dlat0, dlon0) / 1.35, nd, v))
        path = []
        if dist[dn] == INF:
            return None, visited
        cur = dn
        while cur != sn:
            e = prevE[cur]
            path.append(e)
            cur = g.froms[e] if g.tos[e] == cur else g.tos[e]
        path.reverse()
        return path, visited


def main():
    g = Graph(BIN)
    print('nodes=%d edges=%d geom=%d names=%d date=%d' % (g.n, g.m, g.g, g.k, g.date))
    r = Router(g)

    api = json.load(open(os.path.join(HERE, 'api_stops.json'), encoding='utf-8'))
    import verify_datum as V
    ds = []
    for s in api:
        wlng, wlat = s['lng'], s['lat']
        for _ in range(8):
            blng, blat = V.wgs2bd(wlng, wlat)
            wlng += s['lng'] - blng
            wlat += s['lat'] - blat
        d, _, _ = snap_only(g, wlat, wlng)
        ds.append(d)
    ds.sort()
    print('station snap: median=%.0f m  p75=%.0f  p90=%.0f  >100m:%d/%d'
          % (statistics.median(ds), ds[int(len(ds) * .75)], ds[int(len(ds) * .9)],
             sum(1 for x in ds if x > 100), len(ds)))

    # 市中心 -> 科大南门（BD 坐标先反算）
    def bd2wgs(lng, lat):
        wlng, wlat = lng, lat
        for _ in range(8):
            blng, blat = V.wgs2bd(wlng, wlat)
            wlng += lng - blng
            wlat += lat - blat
        return wlng, wlat

    o = bd2wgs(112.938037, 27.823894)
    d = bd2wgs(112.9166826, 27.8965352)
    t = time.time()
    path, visited = r.astar(o[1], o[0], d[1], d[0])
    print('route pts=%s visited=%d %.0f ms' % (len(path) if path else None, visited, (time.time() - t) * 1000))
    if path:
        L = sum(g.lens[e] for e in path) / 100.0
        P = sum(g.poly_len(e) for e in path)
        print('route length %.0f m, poly %.0f m, ETA %.1f min' % (L, P, L / 1.3 / 60))
        print('streets:', [g.nameStr[g.names[e]] for e in path if g.names[e] >= 0][:10])


def snap_only(g, lat, lon):
    best = (1e9, -1, 0.0)
    for i in range(g.m):
        a, b = g.froms[i], g.tos[i]
        d, t = seg_dist(lat, lon, *g.ll(a), *g.ll(b))
        if d < best[0]:
            best = (d, i, t)
    return best


if __name__ == '__main__':
    main()
