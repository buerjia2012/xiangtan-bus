package com.xtbus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 步行路由：双向不需要，湘潭规模双向 A* 与普通 A* 差别可以忽略，这里用标准 A*。
 * 代价 = 长度 / 分类步速 + 过街/台阶惩罚；启发式用直线距离 / 1.4 m/s 保证可采纳。
 */
final class WalkRouter {

    /** 分类步速 m/s：0 其他 1 步道 2 居住区 3 次干 4 主干 5 快速 6 台阶 7 土路 */
    private static final double[] SPEED = {1.20, 1.35, 1.30, 1.30, 1.25, 1.20, 0.55, 1.10};
    private static final double MAX_SPEED = 1.35;

    static final class Step {
        String text;
        double meters;
        int icon;   // 0 直行 1 左转 2 右转 3 到站 4 台阶 5 过街
        /** 该步起点（路口）坐标，供地图打转向点。 */
        double lat;
        double lon;
    }

    static final class Route {
        int[] edges;
        double meters;
        int seconds;
        List<Step> steps = new ArrayList<Step>();
        double[] lats;
        double[] lons;
        double[] cum;
        double startLat;
        double startLon;
        int startNode;
        int destEdge = -1;

        double remaining(double lat, double lon) {
            if (lats == null || lats.length < 2) {
                return meters;
            }
            double bestD = Double.MAX_VALUE;
            int bestSeg = 0;
            double bestT = 0;
            double cos = Math.cos(Math.toRadians(lat));
            for (int i = 0; i + 1 < lats.length; i++) {
                double ax = (lons[i] - lon) * 111320.0 * cos;
                double ay = (lats[i] - lat) * 111320.0;
                double bx = (lons[i + 1] - lon) * 111320.0 * cos;
                double by = (lats[i + 1] - lat) * 111320.0;
                double dx = bx - ax;
                double dy = by - ay;
                double l2 = dx * dx + dy * dy;
                double t = l2 == 0 ? 0 : -(ax * dx + ay * dy) / l2;
                if (t < 0) {
                    t = 0;
                } else if (t > 1) {
                    t = 1;
                }
                double px = ax + t * dx;
                double py = ay + t * dy;
                double d = px * px + py * py;
                if (d < bestD) {
                    bestD = d;
                    bestSeg = i;
                    bestT = t;
                }
            }
            double segLen = cum[bestSeg + 1] - cum[bestSeg];
            double left = meters - (cum[bestSeg] + segLen * bestT);
            return Math.max(0, Math.min(meters, left));
        }

        /** 当前点到整条路线的垂距，用于偏航判定。 */
        double distanceTo(double lat, double lon) {
            if (lats == null || lats.length < 2) {
                return 0;
            }
            double cos = Math.cos(Math.toRadians(lat));
            double best = Double.MAX_VALUE;
            for (int i = 0; i + 1 < lats.length; i++) {
                double ax = (lons[i] - lon) * 111320.0 * cos;
                double ay = (lats[i] - lat) * 111320.0;
                double bx = (lons[i + 1] - lon) * 111320.0 * cos;
                double by = (lats[i + 1] - lat) * 111320.0;
                double dx = bx - ax;
                double dy = by - ay;
                double l2 = dx * dx + dy * dy;
                double t = l2 == 0 ? 0 : -(ax * dx + ay * dy) / l2;
                if (t < 0) {
                    t = 0;
                } else if (t > 1) {
                    t = 1;
                }
                double px = ax + t * dx;
                double py = ay + t * dy;
                double d = px * px + py * py;
                if (d < best) {
                    best = d;
                }
            }
            return Math.sqrt(best);
        }
    }

    private final WalkGraph g;
    private final double[] cost;
    private final double[] dist;
    private final int[] prevEdge;
    private int[] heapNode;
    private double[] heapKey;
    private int heapSize;

    WalkRouter(WalkGraph g) {
        this.g = g;
        cost = new double[g.edgeCount];
        for (int i = 0; i < g.edgeCount; i++) {
            cost[i] = edgeCost(i);
        }
        dist = new double[g.nodeCount];
        prevEdge = new int[g.nodeCount];
        // 懒删除堆：同一节点可能被多次入堆，入堆次数上限是边数而不是节点数，
        // 固定按 nodeCount 开会越界，push() 里再按需扩容。
        heapNode = new int[Math.min(Math.max(g.nodeCount, 16), 1 << 16)];
        heapKey = new double[heapNode.length];
    }

    private double edgeCost(int e) {
        int c = g.cls(e);
        double len = g.length(e);
        double s = SPEED[c];
        if ((g.fl(e) & WalkGraph.F_ROUGH) != 0) {
            len *= 1.15;
        }
        double sec = len / s;
        if ((g.fl(e) & WalkGraph.F_CROSSING) != 0) {
            sec += 12;
        }
        if ((g.fl(e) & WalkGraph.F_STAIRS) != 0) {
            sec += 5;
        }
        return sec;
    }

    /** 返回 null 表示起终点不在同一连通块或吸附失败。 */
    Route route(double sLat, double sLon, double dLat, double dLon, String destName) {
        WalkGraph.Snap ss = g.snap(sLat, sLon);
        WalkGraph.Snap ds = g.snap(dLat, dLon);
        if (!ss.ok() || !ds.ok() || ss.dist > 400 || ds.dist > 400) {
            return null;
        }
        int se = ss.edge;
        int de = ds.edge;
        int sA = g.from(se);
        int sB = g.to(se);
        int dA = g.from(de);
        int dB = g.to(de);
        double sLen = g.length(se);
        double dLen = g.length(de);

        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prevEdge, -1);
        heapSize = 0;

        double cos = Math.cos(Math.toRadians(dLat));
        double sCostA = ss.t * sLen / 1.2;
        double sCostB = (1 - ss.t) * sLen / 1.2;
        dist[sA] = sCostA;
        push(sA, sCostA + heuristic(sA, dLat, dLon, cos));
        if (sB != sA) {
            dist[sB] = sCostB;
            push(sB, sCostB + heuristic(sB, dLat, dLon, cos));
        }

        int target = -1;
        // 硬上限：路网里只要有一个坏节点，A* 就可能长时间出不来，
        // 而查询线程池是共享的，一个卡死的任务会让后面所有「计算中」永远排队。
        long t0 = System.currentTimeMillis();
        long pops = 0;
        while (heapSize > 0) {
            if ((++pops & 255) == 0
                    && (pops > 4000000L || System.currentTimeMillis() - t0 > 8000L)) {
                return null;
            }
            double key = heapKey[0];
            int u = popNode();
            if (u == dA || u == dB) {
                target = u;
                break;
            }
            if (key > dist[u] + heuristic(u, dLat, dLon, cos) + 1e-6) {
                continue;
            }
            for (int k = g.adjStart(u); k < g.adjStart(u + 1); k++) {
                int v = g.adjNode(k);
                int e = g.adjEdge(k);
                double nd = dist[u] + cost[e];
                if (nd < dist[v] - 1e-9) {
                    dist[v] = nd;
                    prevEdge[v] = e;
                    push(v, nd + heuristic(v, dLat, dLon, cos));
                }
            }
        }
        if (target < 0) {
            return null;
        }

        List<Integer> rev = new ArrayList<Integer>();
        int cur = target;
        while (cur != sA && cur != sB && prevEdge[cur] >= 0) {
            rev.add(prevEdge[cur]);
            int e = prevEdge[cur];
            cur = g.from(e) == cur ? g.to(e) : g.from(e);
        }
        int startNode = cur;

        Route r = new Route();
        r.startLat = ss.lat;
        r.startLon = ss.lon;
        r.startNode = startNode;
        r.destEdge = de;
        List<Integer> edges = new ArrayList<Integer>();
        double meters = 0;
        // 起点半边（截到吸附点）
        double snip = startNode == sA ? ss.t * sLen : (1 - ss.t) * sLen;
        meters += snip;
        for (int i = rev.size() - 1; i >= 0; i--) {
            edges.add(rev.get(i));
            meters += g.length(rev.get(i));
        }
        double dsnip = target == dA ? ds.t * dLen : (1 - ds.t) * dLen;
        meters += dsnip;
        double sec = 0;
        for (int i = 0; i < edges.size(); i++) {
            sec += cost[edges.get(i)];
        }
        sec += snip / 1.2 + dsnip / 1.2;

        r.edges = new int[edges.size()];
        for (int i = 0; i < r.edges.length; i++) {
            r.edges[i] = edges.get(i);
        }
        r.meters = meters;
        r.seconds = (int) Math.round(sec);
        // 合理性兜底：湘潭步行图存在片区断链，一旦 A* 绕出「直线 4 倍 + 800 m」以上，
        // 多半是吸附到了断头路，宁可判失败也不要给用户一条 300 公里的路线。
        double straight = WalkCoord.meters(sLat, sLon, dLat, dLon);
        if (meters > straight * 4 + 800) {
            return null;
        }
        buildSteps(r, ss, ds, destName);
        return r;
    }

    private double heuristic(int node, double dLat, double dLon, double cosLat) {
        double dy = (g.lat(node) - dLat) * 111320.0;
        double dx = (g.lon(node) - dLon) * 111320.0 * cosLat;
        return Math.sqrt(dx * dx + dy * dy) / MAX_SPEED;
    }

    /** key 是 f 值（g + h），dist[] 单独存 g 值。 */
    private void push(int node, double key) {
        if (heapSize == heapNode.length) {
            int cap = heapNode.length * 2;
            heapNode = Arrays.copyOf(heapNode, cap);
            heapKey = Arrays.copyOf(heapKey, cap);
        }
        int i = heapSize++;
        heapNode[i] = node;
        heapKey[i] = key;
        while (i > 0) {
            int p = (i - 1) / 2;
            if (heapKey[p] <= heapKey[i]) {
                break;
            }
            double tk = heapKey[p];
            heapKey[p] = heapKey[i];
            heapKey[i] = tk;
            int tn = heapNode[p];
            heapNode[p] = heapNode[i];
            heapNode[i] = tn;
            i = p;
        }
    }

    private int popNode() {
        int top = heapNode[0];
        heapSize--;
        if (heapSize > 0) {
            heapNode[0] = heapNode[heapSize];
            heapKey[0] = heapKey[heapSize];
            int i = 0;
            while (true) {
                int l = i * 2 + 1;
                int rt = l + 1;
                int m = i;
                if (l < heapSize && heapKey[l] < heapKey[m]) {
                    m = l;
                }
                if (rt < heapSize && heapKey[rt] < heapKey[m]) {
                    m = rt;
                }
                if (m == i) {
                    break;
                }
                double tk = heapKey[i];
                heapKey[i] = heapKey[m];
                heapKey[m] = tk;
                int tn = heapNode[i];
                heapNode[i] = heapNode[m];
                heapNode[m] = tn;
                i = m;
            }
        }
        return top;
    }

    // ---------- 指令与折线 ----------

    private void buildSteps(Route r, WalkGraph.Snap ss, WalkGraph.Snap ds, String destName) {
        // 起终点吸附在同一条边上：路径里没有完整边，直接沿这条边走
        if (r.edges.length == 0) {
            buildDirect(r, ss, ds, destName);
            return;
        }
        // 按路由器已经确定的节点顺序拼折线：不用猜方向，也不会被长边带偏
        List<double[]> pts = new ArrayList<double[]>();
        // 先按路由器给的顺序把每个边的入口节点推出来，画折线、打转向点都用它
        int[] entryNode = new int[r.edges.length + 1];
        entryNode[0] = r.startNode;
        for (int i = 0; i < r.edges.length; i++) {
            int e = r.edges[i];
            entryNode[i + 1] = g.from(e) == entryNode[i] ? g.to(e) : g.from(e);
        }
        int cur = r.startNode;
        int count = r.edges.length;
        // 末边单独处理：终点吸附点在它中间，要按吸附参数截断，不能整条走完
        if (count > 0 && r.edges[count - 1] == r.destEdge) {
            count--;
        }
        for (int i = 0; i < count; i++) {
            int e = r.edges[i];
            boolean fwd = g.from(e) == cur;
            int next = entryNode[i + 1];
            double[] pl = g.polyline(e);
            int n = pl.length / 2;
            int skip = i == 0 ? 0 : 1;
            if (fwd) {
                for (int k = skip; k < n; k++) {
                    pts.add(new double[]{pl[k * 2], pl[k * 2 + 1]});
                }
            } else {
                for (int k = n - 1 - skip; k >= 0; k--) {
                    pts.add(new double[]{pl[k * 2], pl[k * 2 + 1]});
                }
            }
            cur = next;
        }
        // 起点吸附点落在前一条边上：从吸附点直接接到首边入口（这段就是 snip）
        pts.add(0, new double[]{ss.lat, ss.lon});
        // 从路径末端沿终点所在边走到吸附点（终点边可能根本不在路径里）
        if (r.destEdge >= 0) {
            double t = g.from(r.destEdge) == cur ? ds.t : 1 - ds.t;
            appendPartial(pts, r.destEdge, cur, t, ds.lat, ds.lon);
        } else {
            pts.add(new double[]{ds.lat, ds.lon});
        }

        r.lats = new double[pts.size()];
        r.lons = new double[pts.size()];
        r.cum = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            r.lats[i] = pts.get(i)[0];
            r.lons[i] = pts.get(i)[1];
            if (i > 0) {
                r.cum[i] = r.cum[i - 1] + WalkCoord.meters(r.lats[i - 1], r.lons[i - 1],
                        r.lats[i], r.lons[i]);
            }
        }
        r.meters = r.cum[pts.size() - 1];

        // 吸附点在首/末边中间时，首末两截不属于任何一条完整边，靠下面的残差校正补上
        double lead = pts.size() > 1
                ? WalkCoord.meters(ss.lat, ss.lon, r.lats[1], r.lons[1]) : 0;

        // 按边名/类型分组生成步骤
        List<Step> steps = new ArrayList<Step>();
        int i = 0;
        while (i < r.edges.length) {
            int e = r.edges[i];
            String nm = g.name(e);
            int cls = g.cls(e);
            double len = g.length(e);
            int j = i + 1;
            while (j < r.edges.length) {
                int e2 = r.edges[j];
                boolean sameName = nm.length() > 0 && nm.equals(g.name(e2));
                boolean bothEmpty = nm.length() == 0 && g.name(e2).length() == 0
                        && g.cls(e2) == cls;
                if (!sameName && !bothEmpty) {
                    break;
                }
                len += g.length(e2);
                j++;
            }
            Step st = new Step();
            st.meters = len + (steps.isEmpty() ? lead : 0);
            st.lat = g.lat(entryNode[i]);
            st.lon = g.lon(entryNode[i]);
            int icon;
            String head;
            if (steps.isEmpty()) {
                head = nm.length() > 0 ? "沿" + nm + "向" + bearingWord(r, i) + "步行"
                        : "向" + bearingWord(r, i) + "步行";
                icon = 0;
            } else {
                int turn = turnOf(r, i);
                icon = turn == 2 ? 2 : turn == 1 ? 1 : 0;
                String tw = turn == 1 ? "左转" : turn == 2 ? "右转" : turn == 3 ? "稍向左" : turn == 4 ? "稍向右" : "继续直行";
                head = tw + (nm.length() > 0 ? "，进入" + nm : "");
            }
            st.icon = icon;
            if ((g.fl(e) & WalkGraph.F_STAIRS) != 0) {
                st.icon = 4;
                head = head + "（上下台阶）";
            }
            st.text = head + " · " + fmt(st.meters);
            steps.add(st);
            i = j;
        }
        // 残差校正：步骤里程之和与折线全程取齐，避免「全程 1.3 公里、步骤加起来 2.2 公里」
        if (!steps.isEmpty()) {
            double sum = 0;
            for (Step s : steps) {
                sum += s.meters;
            }
            Step s = steps.get(steps.size() - 1);
            s.meters = Math.max(1, s.meters + (r.meters - sum));
            s.text = s.text.substring(0, s.text.lastIndexOf(" · ") + 3) + fmt(s.meters);
        }

        Step last = new Step();
        last.icon = 3;
        last.meters = 0;
        last.text = destName != null && destName.length() > 0 ? "到达 " + destName : "到达目的地";
        steps.add(last);
        r.steps = steps;
    }

    /** 起终点吸附在同一条边上：折线取这条边上两点之间的顶点，指令只有一条「沿…步行」。 */
    private void buildDirect(Route r, WalkGraph.Snap ss, WalkGraph.Snap ds, String destName) {
        int e = ss.edge;
        double[] pl = g.polyline(e);
        int n = pl.length / 2;
        int segs = n - 1;
        boolean fwd = ds.t >= ss.t;
        double lo = Math.min(ss.t, ds.t);
        double hi = Math.max(ss.t, ds.t);
        List<double[]> pts = new ArrayList<double[]>();
        pts.add(new double[]{ss.lat, ss.lon});
        for (int k = 1; k < n - 1; k++) {
            double t = segs == 0 ? 0 : (double) k / segs;
            if (t > lo && t < hi) {
                pts.add(new double[]{pl[k * 2], pl[k * 2 + 1]});
            }
        }
        if (!fwd) {
            java.util.Collections.reverse(pts.subList(1, pts.size()));
        }
        pts.add(new double[]{ds.lat, ds.lon});

        r.lats = new double[pts.size()];
        r.lons = new double[pts.size()];
        r.cum = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            r.lats[i] = pts.get(i)[0];
            r.lons[i] = pts.get(i)[1];
            if (i > 0) {
                r.cum[i] = r.cum[i - 1] + WalkCoord.meters(r.lats[i - 1], r.lons[i - 1],
                        r.lats[i], r.lons[i]);
            }
        }
        r.meters = r.cum[pts.size() - 1];
        r.startNode = fwd ? g.from(e) : g.to(e);
        r.seconds = (int) Math.round(r.meters / 1.2);

        String nm = g.name(e);
        double brg = bearing(r.lats[0], r.lons[0],
                r.lats[r.lats.length - 1], r.lons[r.lats.length - 1]);
        Step st = new Step();
        st.icon = 0;
        st.meters = r.meters;
        st.text = (nm.length() > 0 ? "沿" + nm + "向" + dirWord(brg) : "向" + dirWord(brg))
                + "步行 · " + fmt(st.meters);
        List<Step> steps = new ArrayList<Step>();
        steps.add(st);
        Step last = new Step();
        last.icon = 3;
        last.text = destName != null && destName.length() > 0 ? "到达 " + destName : "到达目的地";
        steps.add(last);
        r.steps = steps;
    }

    /** 从 node 一端沿边走到参数 t（0..1，沿行进方向），再加入吸附点。 */
    private void appendPartial(List<double[]> pts, int e, int node, double t,
                               double dsLat, double dsLon) {
        double[] pl = g.polyline(e);
        int segs = pl.length / 2 - 1;
        boolean fwd = g.from(e) == node;
        double pos = t * segs;
        int full = (int) Math.floor(pos);
        if (full > segs) {
            full = segs;
        }
        for (int k = 1; k <= full; k++) {
            int idx = fwd ? k : pl.length / 2 - 1 - k;
            pts.add(new double[]{pl[idx * 2], pl[idx * 2 + 1]});
        }
        pts.add(new double[]{dsLat, dsLon});
    }

    private String bearingWord(Route r, int edgeIdx) {
        double[] a = polylineOf(r, edgeIdx);
        double brg = bearing(a[0], a[1], a[2], a[3]);
        return dirWord(brg);
    }

    private int turnOf(Route r, int edgeIdx) {
        int prev = edgeIdx - 1;
        if (prev < 0) {
            return 0;
        }
        double[] p = polylineOf(r, prev);
        double[] c = polylineOf(r, edgeIdx);
        double b1 = bearing(p[p.length - 4], p[p.length - 3], p[p.length - 2], p[p.length - 1]);
        double b2 = bearing(c[0], c[1], c[2], c[3]);
        double d = b2 - b1;
        while (d > 180) {
            d -= 360;
        }
        while (d < -180) {
            d += 360;
        }
        if (d >= 25 && d <= 60) {
            return 4;
        }
        if (d > 60 && d <= 135) {
            return 2;
        }
        if (d > 135) {
            return 5;
        }
        if (d <= -25 && d >= -60) {
            return 3;
        }
        if (d < -60 && d >= -135) {
            return 1;
        }
        if (d < -135) {
            return 5;
        }
        return 0;
    }

    /** 取边在行进方向上的首/末段，返回 {lat1,lon1,lat2,lon2}。 */
    private double[] polylineOf(Route r, int edgeIdx) {
        int e = r.edges[edgeIdx];
        double[] pl = g.polyline(e);
        double[] out = new double[4];
        double[] want;
        if (edgeIdx == 0) {
            want = new double[]{r.startLat, r.startLon};
        } else {
            int pe = r.edges[edgeIdx - 1];
            double[] pp = g.polyline(pe);
            want = new double[]{pp[0], pp[1]};
        }
        boolean fromIsNear = WalkCoord.meters(pl[0], pl[1], want[0], want[1])
                < WalkCoord.meters(pl[pl.length - 2], pl[pl.length - 1], want[0], want[1]);
        if (fromIsNear) {
            out[0] = pl[0];
            out[1] = pl[1];
            out[2] = pl[2];
            out[3] = pl[3];
        } else {
            int n = pl.length / 2;
            out[0] = pl[(n - 1) * 2];
            out[1] = pl[(n - 1) * 2 + 1];
            out[2] = pl[(n - 2) * 2];
            out[3] = pl[(n - 2) * 2 + 1];
        }
        return out;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static String dirWord(double b) {
        if (b < 22.5 || b >= 337.5) {
            return "北";
        }
        if (b < 67.5) {
            return "东北";
        }
        if (b < 112.5) {
            return "东";
        }
        if (b < 157.5) {
            return "东南";
        }
        if (b < 202.5) {
            return "南";
        }
        if (b < 247.5) {
            return "西南";
        }
        if (b < 292.5) {
            return "西";
        }
        return "西北";
    }

    static String fmt(double m) {
        if (m >= 1000) {
            return String.format(java.util.Locale.CHINA, "%.1f 公里", m / 1000);
        }
        return Math.max(1, Math.round(m)) + " 米";
    }
}
