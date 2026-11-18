"""把抓到的 OSM highway 要素编译成 App 端用的紧凑步行图。

输出：cache/walk_xt.bin
格式（全部小端）：
  44B header: magic "XTWG" / version u16 / flags u16 / nodes u32 / edges u32 /
              geom u32 / names u32 / dataDate u32 / edgeOff u32 / geomOff u32 /
              nameOff u32 / nameBytesLen u32
  nodes  n*8 : i32 lonE7, i32 latE7
  edges  m*20: i32 from, i32 to, i32 lenCm, i32 geomOff, u16 nameIdx, u8 flags, u8 geomCount
  geom   g*4 : i16 dLonE5, dLatE5（相对前一点，首点相对 from 节点）
  names  k*4 : u32 offset（相对 nameBytes 起点）
  nameBytes  : utf-8
"""
import json, math, os, struct, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'cache')
OUT = os.path.join(CACHE, 'walk_xt.bin')
DATA_DATE = 20261115

# 步行可用 / 不可用的判断
DENY_ACCESS = {'no', 'private', 'customers', 'delivery', 'military'}
CLASS_BY_HIGHWAY = [
    (('footway', 'pedestrian', 'path', 'corridor'), 1),
    (('residential', 'living_street', 'service', 'unclassified', 'road'), 2),
    (('tertiary', 'tertiary_link'), 3),
    (('secondary', 'secondary_link'), 4),
    (('primary', 'primary_link'), 5),
    (('steps',), 6),
    (('track', 'cycleway'), 7),
]
F_BRIDGE, F_TUNNEL, F_CROSSING, F_STAIRS, F_ROUGH = 8, 16, 32, 64, 128


def hav(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def class_of(hw):
    for names, c in CLASS_BY_HIGHWAY:
        if hw in names:
            return c
    return 0


def walkable(tags):
    if tags.get('highway') in (None, 'construction', 'proposed', 'raceway', 'bus_stop',
                               'platform', 'elevator', 'corridor'):
        if tags.get('highway') != 'corridor':
            return False
    if tags.get('area') == 'yes':
        return False
    if tags.get('foot') in ('no', 'private'):
        return False
    if tags.get('access') in DENY_ACCESS and tags.get('foot') != 'yes':
        return False
    if tags.get('highway') == 'steps' and tags.get('conveying'):
        pass
    return True


def flags_of(tags, cls):
    f = cls & 7
    if tags.get('bridge') and tags['bridge'] != 'no':
        f |= F_BRIDGE
    if tags.get('tunnel') and tags['tunnel'] != 'no':
        f |= F_TUNNEL
    if tags.get('crossing') or tags.get('footway') == 'crossing':
        f |= F_CROSSING
    if cls == 6:
        f |= F_STAIRS
    if tags.get('surface') in ('unpaved', 'gravel', 'ground', 'dirt', 'grass'):
        f |= F_ROUGH
    return f


def load_ways():
    ways = {}
    for fn in sorted(os.listdir(CACHE)):
        if not fn.startswith('ways_') or not fn.endswith('.json'):
            continue
        d = json.load(open(os.path.join(CACHE, fn), encoding='utf-8'))
        for e in d.get('elements', []):
            if e.get('type') != 'way':
                continue
            ways.setdefault(e['id'], e)
    return list(ways.values())


def main():
    ways = load_ways()
    print('ways loaded:', len(ways))

    node_xy = {}          # id -> (lat, lon)
    use = {}              # id -> 被多少 way 引用
    kept = []
    for w in ways:
        tags = w.get('tags') or {}
        nodes = w.get('nodes') or []
        geom = w.get('geometry') or []
        if len(nodes) < 2 or len(nodes) != len(geom):
            continue
        if not walkable(tags):
            continue
        pts = [(g['lat'], g['lon']) for g in geom]
        kept.append((w, tags, nodes, pts))
        for nid in nodes:
            use[nid] = use.get(nid, 0) + 1
    print('kept ways:', len(kept))

    for w, tags, nodes, pts in kept:
        for nid, (lat, lon) in zip(nodes, pts):
            node_xy[nid] = (lat, lon)

    # T 形路口修补：端点 20m 内若压在别的道路中线上，就把自己插进去，
    # 否则「路画到路口就断」会让整个片区变成孤岛（中国区 OSM 常见）。
    flat = []
    for w, tags, nodes, pts in kept:
        flat.append([list(nodes), list(pts), tags])
    GRID = 0.002
    wcell = {}
    for wi, item in enumerate(flat):
        lats = [p[0] for p in item[1]]
        lons = [p[1] for p in item[1]]
        for ci in range(int(min(lats) / GRID), int(max(lats) / GRID) + 1):
            for cj in range(int(min(lons) / GRID), int(max(lons) / GRID) + 1):
                wcell.setdefault((ci, cj), []).append(wi)

    def pt_seg(lat, lon, a, b):
        ky = 111320.0
        kx = 111320.0 * math.cos(math.radians(lat))
        ax, ay = (a[1] - lon) * kx, (a[0] - lat) * ky
        bx, by = (b[1] - lon) * kx, (b[0] - lat) * ky
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
        return math.hypot(ax + t * dx, ay + t * dy)

    plans = []
    for wi, item in enumerate(flat):
        nodes, pts, _ = item
        for end in (0, -1):
            nid, (lat, lon) = nodes[end], pts[end]
            ci, cj = int(lat / GRID), int(lon / GRID)
            cand = set()
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    cand.update(wcell.get((ci + di, cj + dj), ()))
            best = None
            for wj in cand:
                if wj == wi:
                    continue
                nodes2, pts2, _ = flat[wj]
                if nid in nodes2:
                    continue
                for k in range(len(pts2) - 1):
                    d = pt_seg(lat, lon, pts2[k], pts2[k + 1])
                    if best is None or d < best[0]:
                        best = (d, wj, k)
            if best is not None and best[0] <= 20.0:
                plans.append((best[1], best[2], nid, lat, lon))

    by_way = {}
    for wj, k, nid, lat, lon in plans:
        by_way.setdefault(wj, []).append((k, nid, lat, lon))
    inserted = 0
    for wj, items in by_way.items():
        items.sort(key=lambda x: x[0], reverse=True)
        nodes2, pts2, _ = flat[wj]
        seen_pos = set()
        for k, nid, lat, lon in items:
            if (k, nid) in seen_pos or nid in nodes2:
                continue
            seen_pos.add((k, nid))
            if nid in nodes2:
                continue
            nodes2.insert(k + 1, nid)
            pts2.insert(k + 1, (lat, lon))
            inserted += 1
    print('T-junction inserts:', inserted, 'of', len(plans), 'candidates')

    use = {}
    for nodes, pts, tags in flat:
        for nid in nodes:
            use[nid] = use.get(nid, 0) + 1

    # 在交叉点/端点处切段
    segs = []   # (nodes[], pts[], tags, cls, flags)
    for nodes, pts, tags in flat:
        cls = class_of(tags.get('highway'))
        fl = flags_of(tags, cls)
        start = 0
        for i in range(1, len(nodes) - 1):
            if use.get(nodes[i], 0) >= 2:
                segs.append((nodes[start:i + 1], pts[start:i + 1], tags, cls, fl))
                start = i
        segs.append((nodes[start:], pts[start:], tags, cls, fl))
    print('segments:', len(segs))

    # 单条边的中间几何点上限 255（u8）。超长路段在中间切成链式多段，
    # 否则尾部几何会被丢弃，边长与折线严重不符。
    chain_segs = []
    synth = 0
    for nodes, pts, tags, cls, fl in segs:
        if len(nodes) <= 257:
            chain_segs.append((nodes, pts, tags, cls, fl))
            continue
        s = 0
        first_id = nodes[0]
        while True:
            e = min(s + 257, len(nodes))
            nn = [first_id] + list(nodes[s + 1:e])
            pp = list(pts[s:e])
            if e < len(nodes):
                sid = ('chain', synth)
                synth += 1
                node_xy[sid] = pp[-1]
                nn[-1] = sid
                chain_segs.append((nn, pp, tags, cls, fl))
                first_id = sid
                s = e - 1
            else:
                chain_segs.append((nn, pp, tags, cls, fl))
                break
    if synth:
        print('long segments chained:', synth)
    segs = chain_segs

    # 节点索引：只有切段端点才进节点表，中间几何点走 geom 数组
    # 先做端点邻近合并：OSM 里常有「同一路口画了两个不共享的节点」，不修会断成孤岛
    ends = {}
    for seg in segs:
        for nid in (seg[0][0], seg[0][-1]):
            ends[nid] = node_xy[nid]
    parent = {nid: nid for nid in ends}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    cell = {}
    tol = float(os.environ.get('WALK_MERGE_TOL', '10'))
    for nid, (lat, lon) in ends.items():
        ci, cj = int(lat / 0.0001), int(lon / 0.0001)
        cell.setdefault((ci, cj), []).append(nid)
    for (ci, cj), ids in cell.items():
        near = []
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                near.extend(cell.get((ci + di, cj + dj), ()))
        for a in ids:
            la, oa = ends[a]
            for b in near:
                if b == a:
                    continue
                lb, ob = ends[b]
                if hav(la, oa, lb, ob) <= tol:
                    ra, rb = find(a), find(b)
                    if ra != rb:
                        parent[ra] = rb
    merged = sum(1 for k in parent if find(k) != k)
    print('endpoint merge: %d -> %d (-%d)' % (len(ends), len(set(find(k) for k in parent)), merged))

    idx = {}
    nodes_out = []

    def node_of(nid):
        nid = find(nid)
        if nid not in idx:
            idx[nid] = len(nodes_out)
            nodes_out.append(node_xy[nid])
        return idx[nid]

    for seg in segs:
        node_of(seg[0][0])
        node_of(seg[0][-1])

    names = {}
    name_bytes = bytearray()
    name_offs = []

    def name_idx(nm):
        if not nm:
            return 0xFFFF
        if nm in names:
            return names[nm]
        b = nm.encode('utf-8')
        names[nm] = len(name_offs)
        name_offs.append(len(name_bytes))
        name_bytes.extend(b)
        return names[nm]

    edges = []       # (from, to, len_cm, geomOff, nameIdx, flags, geomCount)
    geom = []        # (dLonE5, dLatE5)
    seen = set()
    for nodes, pts, tags, cls, fl in segs:
        a, b = idx[find(nodes[0])], idx[find(nodes[-1])]
        if a == b:
            continue
        key = (min(a, b), max(a, b))
        if key in seen:
            continue
        seen.add(key)
        length = sum(hav(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
                     for i in range(len(pts) - 1))
        if length < 1.0:
            continue
        # 几何点按「相对前一点」的增量编码，读取端累加还原。
        # 相邻 OSM 节点通常只有几十米，1e-5 度增量远在 i16 范围内；
        # 极端稀疏的中间点（相邻跨度 >3.2km）直接丢弃，避免截断后跳飞。
        goff = len(geom)
        prev_lat, prev_lon = pts[0]
        cnt = 0
        for lat, lon in pts[1:-1]:
            dx = int(round((lon - prev_lon) * 1e5))
            dy = int(round((lat - prev_lat) * 1e5))
            if not (-32768 <= dx <= 32767 and -32768 <= dy <= 32767):
                continue
            if cnt >= 255:
                break
            geom.append((dx, dy))
            cnt += 1
            prev_lat, prev_lon = lat, lon
        nidx = name_idx((tags.get('name') or '').strip())
        edges.append((a, b, int(round(length * 100)), goff, nidx, fl, cnt))

    n, m, g, k = len(nodes_out), len(edges), len(geom), len(name_offs)
    header_len = 44
    edge_off = header_len + n * 8
    geom_off = edge_off + m * 20
    name_off = geom_off + g * 4
    name_bytes_off = name_off + k * 4

    buf = bytearray()
    buf += b'XTWG' + struct.pack('<HHIIIIIIIII', 1, 0, n, m, g, k, DATA_DATE,
                                 edge_off, geom_off, name_off, len(name_bytes))
    for lat, lon in nodes_out:
        buf += struct.pack('<ii', int(round(lon * 1e7)), int(round(lat * 1e7)))
    for e in edges:
        buf += struct.pack('<iiiIhBB', e[0], e[1], e[2], e[3], e[4] if e[4] != 0xFFFF else -1,
                           e[5], e[6])
    for gx, gy in geom:
        buf += struct.pack('<hh', gx, gy)
    for o in name_offs:
        buf += struct.pack('<I', o)
    buf += name_bytes
    assert len(buf) == name_bytes_off + len(name_bytes), (len(buf), name_bytes_off)

    with open(OUT, 'wb') as f:
        f.write(buf)

    total_km = sum(e[2] for e in edges) / 100000.0
    print('\nnodes=%d edges=%d geomPts=%d names=%d' % (n, m, g, k))
    print('total length %.1f km' % total_km)

    # 自检：按编码后的几何累加出的折线长度应与边长度一致
    names_inv = dict((v, k) for k, v in names.items())
    worst = 0.0
    bad = 0
    detail = []
    for e in edges:
        a, _b, lc, goff, _ni, _fl, cnt = e
        plat, plon = nodes_out[a]
        acc = 0.0
        for i in range(cnt):
            dx, dy = geom[goff + i]
            nlat, nlon = plat + dy / 1e5, plon + dx / 1e5
            acc += hav(plat, plon, nlat, nlon)
            plat, plon = nlat, nlon
        tlat, tlon = nodes_out[_b]
        acc += hav(plat, plon, tlat, tlon)
        err = abs(acc - lc / 100.0)
        if err > 1.0:
            bad += 1
            detail.append((err, names_inv.get(_ni, '?'), lc / 100.0, acc, cnt))
        worst = max(worst, err)
    detail.sort(reverse=True)
    for d in detail[:6]:
        print('  worst %.0f name=%s stored=%.0f poly=%.0f gc=%d' % d)
    print('geom check: bad=%d worst=%.1fm  >30m=%d >5m=%d  full255=%d' % (
        bad, worst,
        sum(1 for d in detail if d[0] > 30),
        sum(1 for d in detail if d[0] > 5),
        sum(1 for d in detail if d[4] >= 255)))
    print('file: %s  %.2f MB' % (OUT, os.path.getsize(OUT) / 1e6))
    cls_cnt = {}
    for e in edges:
        cls_cnt[e[5] & 7] = cls_cnt.get(e[5] & 7, 0) + 1
    print('class counts:', dict(sorted(cls_cnt.items())))


if __name__ == '__main__':
    main()
