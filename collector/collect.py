# -*- coding: utf-8 -*-
"""카카오맵 내부 검색 엔드포인트에서 장소 + 평점을 수집해 data/ratings.json에 병합한다.

사용법:
    python collector/collect.py "강남역 카페" "강남역 음식점" [--pages 5]

주의: 비공식 엔드포인트이므로 개인용 저속 수집만. 요청 간 1초 대기.
"""
import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

ENDPOINT = "https://search.map.kakao.com/mapsearch/map.daum"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    "Referer": "https://map.kakao.com/",
}
OUT_PATH = Path(__file__).resolve().parent.parent / "data" / "ratings.json"


def fetch_page(query: str, page: int) -> dict:
    params = urllib.parse.urlencode({"q": query, "msFlag": "A", "sort": "0", "page": page})
    req = urllib.request.Request(f"{ENDPOINT}?{params}", headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.loads(res.read().decode("utf-8"))


def to_entry(p: dict, old: dict | None) -> dict:
    entry = {
        "name": p.get("name", ""),
        "category": p.get("cate_name_depth2") or p.get("cate_name_depth1") or "",
        "lat": float(p["lat"]),
        "lng": float(p["lon"]),
        "address": p.get("new_address") or p.get("address") or "",
        "kakao": None,
        "naver": (old or {}).get("naver"),  # 네이버 데이터는 보존
        "updated": date.today().isoformat(),
    }
    rating = float(p.get("rating_average") or 0)
    count = int(p.get("rating_count") or 0)
    if count > 0:
        entry["kakao"] = {
            "rating": rating,
            "count": count,
            "reviews": int(p.get("reviewCount") or 0),
        }
    return entry


def collect(queries: list[str], pages: int) -> None:
    data: dict = {}
    if OUT_PATH.exists():
        data = json.loads(OUT_PATH.read_text(encoding="utf-8"))

    added = updated = 0
    for query in queries:
        print(f"[수집] {query}")
        for page in range(1, pages + 1):
            try:
                res = fetch_page(query, page)
            except Exception as e:  # noqa: BLE001
                print(f"  page {page} 실패: {e}", file=sys.stderr)
                break
            plist = res.get("place") or []
            if not plist:
                break
            for p in plist:
                pid = str(p.get("confirmid") or "")
                if not pid:
                    continue
                if pid in data:
                    updated += 1
                else:
                    added += 1
                data[pid] = to_entry(p, data.get(pid))
            print(f"  page {page}: {len(plist)}건")
            time.sleep(1)

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"완료: 신규 {added}건, 갱신 {updated}건 → 총 {len(data)}건 ({OUT_PATH})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="카카오 장소+평점 수집")
    parser.add_argument("queries", nargs="+", help='검색어 (예: "강남역 카페")')
    parser.add_argument("--pages", type=int, default=5, help="검색어당 최대 페이지 수 (페이지당 15건)")
    args = parser.parse_args()
    collect(args.queries, args.pages)
