# 湘潭BUS 项目技术文档

> v4.0｜2026-11-18｜仓库 `d:\项目\湘潭出行`。只保留结论与约束，细节以源码为准。

## 1. 概览

| 项 | 内容 |
|---|---|
| 应用 / 包名 | 湘潭BUS / `com.xtbus`（versionCode 1，minSdk 24，targetSdk 34） |
| 形态 | 原生 Android，只读「车来了」湘潭客户端 + 离线地图/步行导航 |
| 构建 | `build.ps1`：aapt2 → javac(-source 8) → d8 → 组包 → zipalign → apksigner，无 Gradle |
| 依赖 | Framework + `org.json` + 4 个 androidx AAR + Mapsforge 0.21，APK 约 2.5 MB |
| 数据 | 车来了 H5（`cityId=173`）；地图与路网为自建资产 |
| 功能 | 附近线路、搜索、线路详情（实时到站/换向/折叠）、走向、路线规划、离线步行导航、离线地图、本地搜索索引、字号设置 |

**硬约束**：不引入 Gradle/R8 混淆；城市固定湘潭；坐标以 WGS-84 为基准、仅输入侧纠偏；口令不入库；接口只读低频，禁止压测与批量抓取；步行导航全离线。

## 2. 架构与构建

`xtbus-app/app/`：`build.ps1`、`res/`、`assets/`（xiangtan.map、walk_xt.bin、xt-map-theme.xml）、`libs/`、`java/com/xtbus/`（18 类）、`tools/walknav/`（Python 管线）。

| 模块 | 类 |
|---|---|
| 网络 | `Api`（参数/签名/AES/YGKJ/缓存/退避）、`Io`（进程级线程池）、`Fmt`（格式化/末班判断） |
| 页面 | `MainActivity`、`LineActivity`、`RouteActivity`+`RouteView`、`PlanActivity`+`PlanDetailActivity`、`SettingsActivity` |
| 导航 | `WalkNavActivity`、`WalkGraph`、`WalkRouter`、`WalkCoord`、`WalkMapPanel` |
| UI | `Tokens`（`_c`/`_l` 双套令牌） |

构建：`cd xtbus-app\app; powershell -ExecutionPolicy Bypass -File .\build.ps1`。需 `ANDROID_SDK_ROOT`、`XTBUS_LIBS`、`XTBUS_STORE_PASS`、`XTBUS_KEY_PASS`（或 `keystore.properties`）。中文路径由脚本建 `D:\xtbus_work` 联接点；类文件先打 `classes.jar` 再交 d8；`.map`/`.bin` 以 `-0` 打包便 mmap。安装 `adb install -r xtbus.apk`。

## 3. 接口与风控

- Base `https://web.chelaile.net.cn/api/`；公共参数 `s=h5&v=9.1.2&vc=1&src=app_xiangtanchuxing&userId=browser_*&h5Id=同值&sign=1&cityId=173&geo_lat&geo_lng&lat&lng&gpstype=gcj`。
- 签名 `cryptoSign = MD5(payload 去花括号、":"→"="、","→"&" + "qwihrnbtmj")`，只签官方会签名的接口与该接口 payload。
- 响应剥离 `**YGKJ … YGKJ##` 取 `jsonr.data`；含 `encryptResult` 时 AES-128-ECB（key `422556651C7F7B2B5C266EED06068230`，PKCS5/7）。
- 接口：`encryptedNearlines`（附近，`cityState=2`，签名）、`encryptedLineDetail`（详情，签名）、`busList`（实时到站）、`clientSearch`（搜索，`count=3`）、`lineRoute`（走向，`s=android`）、`encryptedStnDetail`（站点）、`cityLineList`（全市线路）。
- 请求头：UA 用 `WebSettings.getDefaultUserAgent`，Referer 带 `?src=…&lat=…&lng=…`，`Content-Type: text/plain;charset=UTF-8`，搜索 `Accept: text/plain`。
- `direction` 不是服务端过滤条件：同一 lineId 传 0/1 的 buses 一致，对向车辆来自 `otherlines[0]`；换向即 `direction = 1 ^ 当前`、`targetOrder=-1`。
- 风控：每安装唯一 `userId=browser_<ts36>_<rand>`+`h5Id`；轮询 30 秒 + 抖动；失败分 `OK`/`BUSINESS`/`RISK`（停自动刷新，2m→5m→15m→30m 退避）/`NETWORK`，退避持久化；不做共享出口、代理池、UA 轮换；验证单设备 30–60 秒/接口，遇风控信号停 24 小时。

## 4. 功能

**首页**：搜索框 + 最近使用 chips（≤6 条）+ 附近站点卡。到站牌三态（朱砂≤3 分 / 墨绿 / 虚线暂无）。超 30 秒 `onResume` 刷新、可见时 45 秒周期刷新、下拉最小 5 秒；失败保留最近使用与重试，离线冷启动不空白；缓存键量化 4 位小数（约 10 米），上限 200 文件/5 MB；FINE 或 COARSE 任一授予即已授权。

**搜索**：提交式，无联想；限 30 字符，拦截 `%` 等字符；有索引时 local-first，未命中走网络并合并；失败保留返回与重试；站点行不展示未实现功能。

**详情**：摘要 + 最多 3 张车辆卡 + 站序。车辆统一 `BUS_CMP`（已到站 → travelTime → distanceToSc → order → 车牌），同站多车只显示最近一辆；三态「正常/末班车已过本站/暂无车辆在线」，用 `lastTime` 区分已收班；`order<=0` 不展示。站序过滤 `order<=0 || >=500`（含 999 占位）后升序，站号取 order；折叠 `foldLimit = min(最近车辆 order, 候车站 order)`；候车站朱砂三重编码不折叠；滚动锚点屏高 1/3、布局完成后再滚一次；30 秒刷新仅前台并还原 `scrollY`；换向同步最近使用。

**规划**：切换策略后校验 `strategy` 重发；实时 key 为 `lineId+sId`；首段上车取 `max(推算, 实时)`；任一段末班已过即置灰；`foreground` 控制刷新。

**设置**：单页四组六行（字号、定位方式、定位权限、清除缓存、版本、数据来源），齿轮入口，切字号 `recreate()` 并保存搜索态。不做提醒、深色、账号、检查更新。

**走向**：Canvas 折线、自动缩放、首尾标站名、抽样画站点；标题「N路 · 线路走向」，不出现开发术语。

## 5. 数据

**离线地图**：`xiangtan.map` 1.19 MB，v3，bbox `27.70,112.78,28.02,113.10`；OSM 快照 2026-11-14T21:20:41Z，osmosis + map-writer 0.9.1 切图。Mapsforge 0.21（LGPL，纯 Java）；Maven 可直取最新 0.25，实测兼容无需重切。首启复制到 `filesDir`，`TileRendererLayer`+`Polyline`+`Marker`，主题 `xt-map-theme.xml`。**重切地图必须同步改 `WalkMapPanel` 的 `ver`**。换 MapLibre 需 +12.8 MB so 与 Vulkan，不建议。

**步行导航**：桌面预计算 + 端上纯 Java A\*。`walk_xt.bin` 600 KB（9,014 节点 / 12,268 边 / 398 路名），覆盖市区与县城。坐标：接口站点 BD-09、`transfer` 为 GCJ-02、GPS/OSM 为 WGS-84，进路由统一 WGS 并三基准试吸附。代价=长度/步速+过街 12 s+台阶 5 s；步速 1.2 m/s；偏航 45 m/3 点/15 s/≤6 次；吸附失败提示「附近没有可用步道」。入口仅规划详情与线路详情两处。限制：无坡度与 `sidewalk`、约 156/733 站点离路网 >100 m、不做任意 POI。

**搜索索引**：JSON + 内存索引，随包种子 gzip；匹配 精确→前缀→包含→拼音首字母→全拼，线路 10 + 站点 10；7 天线列表差分、每会话 ≤5 条详情（30 秒间隔）、30 天校验；不在手机端全量爬取；界面零改动。

**管线**：`fetch_osm_ways.py`、`build_walkgraph.py`、`probe_api_stops.py`、`validate_route.py`。

## 6. 界面

暖纸 + 墨 + 朱砂；到站分钟是唯一主角（唯一允许 ≥24sp 粗体 + 彩色容器）；饱和色只用于号牌与到站牌（一屏 <8%），朱砂仅表示即将/候车站/失败；只有卡（白底 1dp 描边）与牌（实心）两种容器。

| 令牌 | 小字版 | 大字版 |
|---|---|---|
| 到站数字/单位 | 24/12sp | 40/16sp |
| 标题/线路号/站名/终点/元信息 | 18/17/15/14/11 | 26/24/21/17/14 |
| 边距/卡片内边距 | 16/14dp | 20/18dp |
| 行高/触控 | 56/48dp | 76/56dp |
| 到站牌/号牌最小宽 | 64×40/44dp | 96×56/58dp |

色值：`paper #FAF7F2`、`card #FFFFFF`、`sunken #F2ECE2`、`line #E8E0D3`、`ink #1E1A16`、`ink2 #665E53`、`ink3 #8C8377`、`pine #16302B`、`pine-90 #E7EDEA`、`signal #C0452F`、`signal-90 #F7E4DD`、`amber #9A6B14`。两版非倍数关系：大字版层级更陡、砍第二层信息、长终点名 2 行、站序默认折叠、换向/刷新固定底栏。令牌在 `values/colors.xml`、`dimens.xml(_c)`、`dimens_large.xml(_l)`、`Tokens.java`；间距 4dp 网格；textSize 用 sp，容器 `wrap_content + minHeight`。

页面：首页去横幅、定位并入副标题、一站点一张卡；搜索独立页、结果去 ETA 位、空态给最近搜索/常用线路/附近站点；详情 hero 卡放最近一班、站序竖向时间轴、候车站整行浅朱砂；设置页字号第一行、选中态用墨绿。

## 7. 质量与合规

- 走查 21 项已全修（唯一未完全达成：展开已过站后视口接近终点时会下移约 150dp）；代码审查 49 修 / 2 部分 / 2 保留（列表未改 RecyclerView、详情页未走令牌），中文文案未全量入 `strings.xml`。
- 回归约束：乘车段展开/收起、中间站 ≤5 + 「还经过 X 站」、反向倒序、顶部无车不自动滚动、应用名「湘潭公交车」、包名不变。
- 已知限制：第三方接口可能变更；603/605 等无实时车辆；详情无逐站 ETA；走向不画站点；路网有空洞；字体放大 1.3 倍以上有截断风险；无站点详情页。
- 合规：车来了数据只读低频、不转售；OSM 按 ODbL 署名并标注数据日期，衍生库同样以 ODbL 提供；Mapsforge LGPL 需保留可替换性声明；不内置商业地图瓦片，不提供路线分享截图。

> 由早期 12 份文档归并精简，以当前源码与 `res/values/*.xml` 为准。
