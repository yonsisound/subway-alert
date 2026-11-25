#!/usr/bin/env python3
"""수도권 노선 데이터(subway_data.json) 생성기.

원본: github.com/wantaekchoi/seoul-subway (MIT) 의 data/network.json(OpenStreetMap ODbL 기반)
      + data/timetable-1.json(서울 열린데이터광장 1~9호선 평일 시간표)
사용: python3 tools/build_data.py <seoul-subway 경로> [출력경로]

역간 시간(초):
  - 1~9호선: 평일 시간표의 연속 정차역 경과시간 중앙값 (정차시간 포함)
  - 그 외 노선: 시간표 구간으로 회귀한 '거리→시간' 식으로 추정
"""
import json, sys, statistics, datetime, os
from collections import defaultdict

src = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", "data", "subway_data.json")
net = json.load(open(os.path.join(src, "data/network.json"), encoding="utf-8"))
tt = json.load(open(os.path.join(src, "data/timetable-1.json"), encoding="utf-8"))
today = datetime.date.today().isoformat()

# 아직 개통 안 한 역 제외
stations = [s for s in net["stations"] if not s.get("o") or s["o"] <= today]
keep = {s["i"] for s in stations}
segs = [g for g in net["segments"] if g["a"] in keep and g["b"] in keep and g.get("t", 0) <= 9999]

# ---- 원본 데이터 보정 ----
name = {s["i"]: s["n"] for s in stations}
def sid(n, pred=lambda i: True):
    c = [s["i"] for s in stations if s["n"] == n and pred(s["i"])]
    return c[0] if c else None
def nbrs(i): return {name[g["b"] if g["a"] == i else g["a"]] for g in segs if i in (g["a"], g["b"])}
fix_drop = set()
# 인천 서구청 -> 서해구청 개명으로 옛 이름 역이 중복
if sid("서해구청") is not None and sid("서구청") is not None: fix_drop.add(sid("서구청"))
# 우이신설 '삼양' 중복 (화계와 안 붙은 쪽 제거)
dup = [s["i"] for s in stations if s["n"] == "삼양"]
if len(dup) > 1: fix_drop.update(i for i in dup if "화계" not in nbrs(i))
stations = [s for s in stations if s["i"] not in fix_drop]
segs = [g for g in segs if g["a"] not in fix_drop and g["b"] not in fix_drop]
# 경의중앙 원덕-지평 -> 용문-지평
wd, ym, jp = sid("원덕"), sid("용문"), sid("지평")
for g in segs:
    if g["l"] == "경의·중앙" and {g["a"], g["b"]} == {wd, jp}:
        m2 = next((x["m"] for x in segs if x["l"] == "경의·중앙" and {x["a"], x["b"]} == {wd, ym}), 0)
        g["a"], g["b"], g["m"] = ym, jp, max(500, g["m"] - m2)
# 경의중앙 서강대-가좌 사이 홍대입구 누락
hd, sg, gj = sid("홍대입구"), sid("서강대"), sid("가좌")
for g in list(segs):
    if g["l"] == "경의·중앙" and {g["a"], g["b"]} == {sg, gj} and hd is not None:
        segs.remove(g)
        segs.append({**g, "a": sg, "b": hd, "m": g["m"] // 2})
        segs.append({**g, "a": hd, "b": gj, "m": g["m"] - g["m"] // 2})
# 공항철도 공덕-마곡나루 사이 홍대입구·디지털미디어시티 누락
import math
pos = {s["i"]: (s["lat"], s["lon"]) for s in stations}
def geo(a, b):
    (la1, lo1), (la2, lo2) = pos[a], pos[b]
    return math.hypot((la1 - la2) * 111000, (lo1 - lo2) * 88000)
def insert_chain(line, a, b, mids):
    ia, ib = sid(a), sid(b); im = [sid(m) for m in mids]
    if None in im or ia is None or ib is None: print("경고: 보정 실패", line, a, b); return
    for g in list(segs):
        if g["l"] == line and {g["a"], g["b"]} == {ia, ib}:
            segs.remove(g); chain = [ia] + im + [ib]
            parts = [geo(chain[k], chain[k + 1]) for k in range(len(chain) - 1)]
            for k in range(len(chain) - 1):
                segs.append({**g, "a": chain[k], "b": chain[k + 1], "m": int(g["m"] * parts[k] / sum(parts))})
insert_chain("공항철도", "공덕", "마곡나루", ["홍대입구", "디지털미디어시티"])
print("보정: 제거역", [name[i] for i in fix_drop])

# 급행 건너뛰기 구간 제거: 같은 노선에서 중간역들을 거쳐 비슷한 거리로 갈 수 있으면 급행 선로로 보고 뺀다
import heapq
def drop_express(segs):
    by_line = defaultdict(list)
    for k, g in enumerate(segs): by_line[g["l"]].append(k)
    drop = set()
    for line, ks in by_line.items():
        adj = defaultdict(list)
        for k in ks:
            g = segs[k]; adj[g["a"]].append((g["b"], g["m"], k)); adj[g["b"]].append((g["a"], g["m"], k))
        for k in ks:
            g = segs[k]
            dist = {g["a"]: 0}; hops = {g["a"]: 0}; pq = [(0, g["a"])]
            while pq:
                d, u = heapq.heappop(pq)
                if u == g["b"] or d > g["m"] * 1.35: break
                if d > dist[u]: continue
                for v, m, kk in adj[u]:
                    if kk == k or kk in drop: continue
                    if d + m < dist.get(v, 1e18): dist[v] = d + m; hops[v] = hops[u] + 1; heapq.heappush(pq, (d + m, v))
            if g["b"] in dist and hops[g["b"]] >= 2 and dist[g["b"]] <= g["m"] * 1.35: drop.add(k)
    print("급행 건너뛰기 구간 제거:", len(drop))
    return [g for k, g in enumerate(segs) if k not in drop]
segs = drop_express(segs)

# 시간표 기반 구간 시간
samples = defaultdict(list)
for tr in tt:
    s, o, l = tr["s"], tr["o"], tr["l"]
    for k in range(len(s) - 1):
        dt = o[k + 1] - o[k]
        if 30 <= dt <= 900:
            samples[(l, min(s[k], s[k + 1]), max(s[k], s[k + 1]))].append(dt)

pairs = []
for g in segs:
    key = (g["l"], min(g["a"], g["b"]), max(g["a"], g["b"]))
    if key in samples and len(samples[key]) >= 5:
        pairs.append((g["m"], statistics.median(samples[key])))
# 최소제곱: sec = a*m + b
n = len(pairs); sx = sum(p[0] for p in pairs); sy = sum(p[1] for p in pairs)
sxx = sum(p[0] ** 2 for p in pairs); sxy = sum(p[0] * p[1] for p in pairs)
a = (n * sxy - sx * sy) / (n * sxx - sx * sx); b = (sy - a * sx) / n
print(f"시간표 구간 {n}개, 추정식 sec = {a:.4f}*m + {b:.1f}")

# 노선별 평균 속도 보정 (광역/급행 성격 노선은 더 빠름)
FAST = {"GTX-A": 0.45, "공항철도": 0.8, "신분당": 0.8, "경강": 0.85, "경춘": 0.85, "서해": 0.85}
edges, est = [], 0
for g in segs:
    key = (g["l"], min(g["a"], g["b"]), max(g["a"], g["b"]))
    if key in samples and len(samples[key]) >= 5:
        sec = statistics.median(samples[key])
    else:
        sec = (a * g["m"] + b) * FAST.get(g["l"], 1.0); est += 1
    edges.append({"l": g["l"], "a": g["a"], "b": g["b"], "s": int(round(sec)), "m": g["m"]})
print(f"구간 {len(edges)}개 중 추정 {est}개")

# 갈림길에서 같은 노선이어도 직통 운행이 없는 방향 (환승 필요)
NO_THROUGH = [
    ("1", "구일", "구로", "가산디지털단지"), ("1", "광명", "금천구청", "석수"), ("1", "서동탄", "병점", "세마"),
    ("2", "도림천", "신도림", "대림(구로구청)"), ("2", "도림천", "신도림", "문래"),
    ("2", "용답", "성수", "뚝섬"), ("2", "용답", "성수", "건대입구"),
    ("5", "둔촌동", "강동", "길동"), ("6", "역촌", "응암", "구산"),
    ("경의·중앙", "신촌", "가좌", "홍대입구"), ("경춘", "광운대", "상봉", "중랑"),
]
sname = {s["i"]: s["n"] for s in stations}
nt = []
for l, a, j, b in NO_THROUGH:
    js = [i for i, n in sname.items() if n == j]
    ok = False
    for ji in js:
        nb = {}
        for g in segs:
            if g["l"] != l: continue
            if g["a"] == ji: nb[sname[g["b"]]] = g["b"]
            if g["b"] == ji: nb[sname[g["a"]]] = g["a"]
        if a in nb and b in nb: nt.append([l, nb[a], ji, nb[b]]); ok = True
    if not ok: print("경고: 갈림길 규칙 적용 실패", l, a, j, b)

data = {
    "version": int(datetime.datetime.now().strftime("%Y%m%d%H")),
    "generated": today,
    "source": "OpenStreetMap(ODbL)·Wikidata·서울 열린데이터광장, via wantaekchoi/seoul-subway",
    "lines": [{"id": l["id"], "name": l["name"], "color": l["color"]} for l in net["lines"]],
    "stations": [{"i": s["i"], "n": s["n"], "lat": s["lat"], "lon": s["lon"]} for s in stations],
    "edges": edges,
    "transferSec": 360,
    "transfers": {},
    "noThrough": nt,
}
os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
json.dump(data, open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
print("저장:", out, os.path.getsize(out), "bytes, 역", len(stations))
