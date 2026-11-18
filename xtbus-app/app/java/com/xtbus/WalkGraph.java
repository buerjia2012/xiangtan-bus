package com.xtbus;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 湘潭步行紧凑图（assets/walk_xt.bin）。
 *
 * 设计目标是「小、冷启动快、可 mmap」：
 * - 只读映射，不复制到堆；asset 用 aapt2 -0 不压缩时可直接从 APK 映射，
 *   否则回退到首启复制一次到私有目录。
 * - 边是无向的，CSR 在加载时构建一次。
 */
final class WalkGraph {

    private static final String TAG = "xtbus-walk";
    private static final String ASSET = "walk_xt.bin";
    private static final String FILE = "walk_xt.bin";

    static final int F_BRIDGE = 8;
    static final int F_TUNNEL = 16;
    static final int F_CROSSING = 32;
    static final int F_STAIRS = 64;
    static final int F_ROUGH = 128;

    final int nodeCount;
    final int edgeCount;
    final int dataDate;

    private final int[] nodeLon;
    private final int[] nodeLat;
    private final int[] from;
    private final int[] to;
    private final int[] lenCm;
    private final int[] geomOff;
    private final short[] geom;      // 经纬度增量，1e-5 度，相对 from 节点
    private final short[] nameIdx;
    private final byte[] flags;
    private final int[] geomCnt;
    private final String[] names;

    private final int[] adjStart;
    private final int[] adjNode;
    private final int[] adjEdge;

    private static volatile WalkGraph sInstance;

    static WalkGraph get(Context ctx) {
        WalkGraph g = sInstance;
        if (g == null) {
            synchronized (WalkGraph.class) {
                g = sInstance;
                if (g == null) {
                    try {
                        g = new WalkGraph(ctx.getApplicationContext());
                        sInstance = g;
                    } catch (Throwable e) {
                        Log.w(TAG, "load fail", e);
                        return null;
                    }
                }
            }
        }
        return g;
    }

    private WalkGraph(Context ctx) throws Exception {
        ByteBuffer b = map(ctx);
        b.order(ByteOrder.LITTLE_ENDIAN);
        if (b.get(0) != 'X' || b.get(1) != 'T' || b.get(2) != 'W' || b.get(3) != 'G') {
            throw new IllegalStateException("bad magic");
        }
        b.position(4);
        int version = b.getShort() & 0xFFFF;
        if (version != 1) {
            throw new IllegalStateException("bad version " + version);
        }
        b.getShort();
        nodeCount = b.getInt();
        edgeCount = b.getInt();
        int geomCount = b.getInt();
        int nameCount = b.getInt();
        dataDate = b.getInt();
        int edgeOff = b.getInt();
        int geomOffBytes = b.getInt();
        int nameOff = b.getInt();
        int nameBytesLen = b.getInt();

        nodeLon = new int[nodeCount];
        nodeLat = new int[nodeCount];
        int p = 44;
        for (int i = 0; i < nodeCount; i++) {
            nodeLon[i] = b.getInt(p);
            nodeLat[i] = b.getInt(p + 4);
            p += 8;
        }

        from = new int[edgeCount];
        to = new int[edgeCount];
        lenCm = new int[edgeCount];
        geomOff = new int[edgeCount];
        nameIdx = new short[edgeCount];
        flags = new byte[edgeCount];
        geomCnt = new int[edgeCount];
        p = edgeOff;
        for (int i = 0; i < edgeCount; i++) {
            from[i] = b.getInt(p);
            to[i] = b.getInt(p + 4);
            lenCm[i] = b.getInt(p + 8);
            geomOff[i] = b.getInt(p + 12);
            nameIdx[i] = b.getShort(p + 16);
            flags[i] = b.get(p + 18);
            geomCnt[i] = b.get(p + 19) & 0xFF;
            p += 20;
        }

        geom = new short[geomCount * 2];
        p = geomOffBytes;
        for (int i = 0; i < geom.length; i++) {
            geom[i] = b.getShort(p);
            p += 2;
        }

        names = new String[nameCount];
        int[] nameOffsets = new int[nameCount];
        p = nameOff;
        for (int i = 0; i < nameCount; i++) {
            nameOffsets[i] = b.getInt(p);
            p += 4;
        }
        byte[] nb = new byte[nameBytesLen];
        for (int i = 0; i < nameBytesLen; i++) {
            nb[i] = b.get(p + i);
        }
        for (int i = 0; i < nameCount; i++) {
            int s = nameOffsets[i];
            int e = i + 1 < nameCount ? nameOffsets[i + 1] : nameBytesLen;
            if (s >= 0 && e >= s && e <= nameBytesLen) {
                names[i] = new String(nb, s, e - s, "UTF-8");
            } else {
                names[i] = "";
            }
        }

        adjStart = new int[nodeCount + 1];
        for (int i = 0; i < edgeCount; i++) {
            adjStart[from[i] + 1]++;
            adjStart[to[i] + 1]++;
        }
        for (int i = 0; i < nodeCount; i++) {
            adjStart[i + 1] += adjStart[i];
        }
        adjNode = new int[edgeCount * 2];
        adjEdge = new int[edgeCount * 2];
        int[] fill = new int[nodeCount];
        for (int i = 0; i < edgeCount; i++) {
            int a = from[i];
            int c = to[i];
            adjNode[adjStart[a] + fill[a]] = c;
            adjEdge[adjStart[a] + fill[a]] = i;
            fill[a]++;
            adjNode[adjStart[c] + fill[c]] = a;
            adjEdge[adjStart[c] + fill[c]] = i;
            fill[c]++;
        }
    }

    private static ByteBuffer map(Context ctx) throws Exception {
        AssetFileDescriptor afd = null;
        try {
            afd = ctx.getAssets().openFd(ASSET);
            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
            FileChannel ch = fis.getChannel();
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(), afd.getLength());
            return mb;
        } catch (Throwable ignored) {
            // asset 被压缩时 openFd 会抛，回退到复制一次
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
        }
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists() || f.length() == 0) {
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            InputStream in = ctx.getAssets().open(ASSET);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.close();
            in.close();
            if (!tmp.renameTo(f)) {
                throw new IllegalStateException("copy asset failed");
            }
        }
        FileInputStream fis = new FileInputStream(f);
        return fis.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, f.length());
    }

    double lon(int i) {
        return nodeLon[i] / 1e7;
    }

    int adjStart(int node) {
        return adjStart[node];
    }

    int adjNode(int k) {
        return adjNode[k];
    }

    int adjEdge(int k) {
        return adjEdge[k];
    }

    int from(int e) {
        return from[e];
    }

    int to(int e) {
        return to[e];
    }

    double lat(int i) {
        return nodeLat[i] / 1e7;
    }

    String name(int edge) {
        int i = nameIdx[edge];
        return i < 0 || i >= names.length ? "" : names[i];
    }

    int cls(int edge) {
        return flags[edge] & 7;
    }

    int fl(int edge) {
        return flags[edge] & 0xFF;
    }

    double length(int edge) {
        return lenCm[edge] / 100.0;
    }

    /** 命中：边、边上比例、距离、投影点。 */
    static final class Snap {
        int edge = -1;
        double t;
        double dist;
        double lat;
        double lon;

        boolean ok() {
            return edge >= 0;
        }
    }

    Snap snap(double lat, double lon) {
        Snap best = new Snap();
        best.dist = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(lat));
        for (int e = 0; e < edgeCount; e++) {
            int cnt = geomCnt[e];
            double aLat = this.lat(from[e]);
            double aLon = lon(from[e]);
            for (int k = 0; k <= cnt; k++) {
                double bLat;
                double bLon;
                if (k == cnt) {
                    bLat = this.lat(to[e]);
                    bLon = lon(to[e]);
                } else {
                    int gi = geomOff[e] + k;
                    bLat = aLat + geom[gi * 2 + 1] / 1e5;
                    bLon = aLon + geom[gi * 2] / 1e5;
                }
                double d = segDist(lat, lon, aLat, aLon, bLat, bLon, cosLat, best, e, k, cnt);
                if (d < best.dist) {
                    best.dist = d;
                }
                aLat = bLat;
                aLon = bLon;
            }
        }
        return best;
    }

    private double segDist(double plat, double plon, double alat, double alon,
                           double blat, double blon, double cosLat, Snap out,
                           int e, int k, int cnt) {
        double ax = (alon - plon) * 111320.0 * cosLat;
        double ay = (alat - plat) * 111320.0;
        double bx = (blon - plon) * 111320.0 * cosLat;
        double by = (blat - plat) * 111320.0;
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
        double d = Math.sqrt(px * px + py * py);
        if (d < out.dist) {
            out.edge = e;
            out.t = (k + t) / (cnt + 1);
            out.lat = alat + t * (blat - alat);
            out.lon = alon + t * (blon - alon);
        }
        return d;
    }

    /** 一条边的完整折线（含两端节点），返回 纬度/经度 交替数组。 */
    double[] polyline(int edge) {
        int cnt = geomCnt[edge];
        double[] r = new double[(cnt + 2) * 2];
        double aLat = lat(from[edge]);
        double aLon = lon(from[edge]);
        r[0] = aLat;
        r[1] = aLon;
        for (int k = 0; k < cnt; k++) {
            int gi = geomOff[edge] + k;
            aLat += geom[gi * 2 + 1] / 1e5;
            aLon += geom[gi * 2] / 1e5;
            r[(k + 1) * 2] = aLat;
            r[(k + 1) * 2 + 1] = aLon;
        }
        r[(cnt + 1) * 2] = lat(to[edge]);
        r[(cnt + 1) * 2 + 1] = lon(to[edge]);
        return r;
    }
}
