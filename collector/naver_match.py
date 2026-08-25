# -*- coding: utf-8 -*-
"""[실험적] ratings.json의 장소를 네이버지도에서 검색해 평점/리뷰 수를 채운다.

네이버는 curl 수준 요청을 ncaptcha로 차단하므로 Playwright 실브라우저가 필수.
    pip install playwright
    playwright install chromium
    python collector/naver_match.py [--limit 30]

- 요청 간 4~7초 대기 (차단 방지). 차단 감지 시 즉시 중단.
- 매칭 기준: 검색 1위 결과가 좌표 300m 이내일 때만 채택.
"""
import argparse
import json
import math
import random
import re
import sys
import time
from datetime import date
from pathlib import Path

OUT_PATH = Path(__file__).resolve().parent.parent / "data" / "ratings.json"
SEARCH_URL = "https://map.naver.com/p/api/search/allSearch?query={query}&type=all&searchCoord={lng};{lat}"


def distance_m(lat1, lng1, lat2, lng2):
    dx = (lng2 - lng1) * 88000 * math.cos(math.radians(lat1))
    dy = (lat2 - lat1) * 111000
    return math.hypot(dx, dy)


def pick_number(item, *keys):
    for k in keys:
        v = item.get(k)
        if v is None:
            continue
        try:
            n = float(re.sub(r"[^\d.]", "", str(v)) or 0)
        except ValueError:
            continue
        if n > 0:
            return n
    return None


def match_one(page, pid, entry):
    """네이버 검색 → 좌표로 검증 → naver 필드 반환 (없으면 None)"""
    query = f"{entry['name']} {' '.join(entry['address'].split()[:2])}"
    url = SEARCH_URL.format(
        query=query.replace(" ", "%20"), lng=entry["lng"], lat=entry["lat"]
    )
    body = page.evaluate(
        "url => fetch(url).then(r => r.text())", url
    )
    data = json.loads(body)
    place = (data.get("result") or {}).get("place") or {}
    items = place.get("list") or []
    if not items:
        meta = (data.get("result") or {}).get("metaInfo") or {}
        if "ncaptcha" in str(meta.get("pageId", "")) and data.get("result", {}).get("ncaptcha"):
            raise BlockedError()
        return None

    item = items[0]
    try:
        n_lat, n_lng = float(item.get("y", 0)), float(item.get("x", 0))
    except (TypeError, ValueError):
        return None
    if distance_m(entry["lat"], entry["lng"], n_lat, n_lng) > 300:
        return None

    naver = {"id": item.get("id"), "updated": date.today().isoformat()}
    rating = pick_number(item, "starScore", "avgRating", "reviewScore")
    if rating:
        naver["rating"] = rating
        naver["count"] = int(pick_number(item, "reviewCount", "starScoreCount") or 0)
    visitor = pick_number(item, "placeReviewCount", "visitorReviewCount")
    blog = pick_number(item, "blogCafeReviewCount", "reviewCount")
    if visitor is not None:
        naver["visitorReviews"] = int(visitor)
    if blog is not None:
        naver["blogReviews"] = int(blog)
    return naver if len(naver) > 2 else None


class BlockedError(Exception):
    pass


def main(limit: int):
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("playwright가 필요합니다: pip install playwright && playwright install chromium", file=sys.stderr)
        sys.exit(1)

    data = json.loads(OUT_PATH.read_text(encoding="utf-8"))
    targets = [(pid, e) for pid, e in data.items() if not e.get("naver")][:limit]
    if not targets:
        print("네이버 데이터가 없는 장소가 없습니다.")
        return

    print(f"대상 {len(targets)}건 (요청 간 4~7초 대기)")
    done = 0
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        page = browser.new_page(locale="ko-KR")
        # 첫 방문으로 세션 쿠키 확보
        page.goto("https://map.naver.com/", wait_until="domcontentloaded")
        time.sleep(3)

        for pid, entry in targets:
            try:
                naver = match_one(page, pid, entry)
            except BlockedError:
                print("차단 감지 — 중단합니다. 몇 시간 뒤 다시 시도하세요.", file=sys.stderr)
                break
            except Exception as e:  # noqa: BLE001
                print(f"  {entry['name']}: 실패 ({e})", file=sys.stderr)
                naver = None
            if naver:
                data[pid]["naver"] = naver
                done += 1
                print(f"  {entry['name']}: OK {naver}")
            else:
                print(f"  {entry['name']}: 매칭 없음")
            time.sleep(random.uniform(4, 7))

        browser.close()

    OUT_PATH.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"완료: {done}건 채움 → {OUT_PATH}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="네이버 평점 매칭 (실험적)")
    parser.add_argument("--limit", type=int, default=30, help="이번 실행에서 처리할 최대 건수")
    args = parser.parse_args()
    main(args.limit)
