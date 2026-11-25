# 지하철 알림 (subway-alert)

수도권 전철 출발역·도착역을 고르면 **환승역·도착역 한 정거장 전**에 진동 알림을 주는 개인용 안드로이드 앱.

## 기능
- 경로 검색: 최소시간 / 최소환승, 대안 경로 최대 3개
- 환승역 변경: 경로 상세에서 환승 버튼 → 같은 두 노선이 만나는 다른 역으로 재계산
- 탑승 안내: 시간 기반 위치 추정 + 위치 신호 보정, 상단 상시 알림, 한 정거장 전/도착 시 진동 알림
- 수동 보정: 다음 역 도착 / 현재 역 수정 / 환승 열차 탑승
- 노선 업데이트: 앱 상단 버튼 → 이 저장소 `data/subway_data.json` 을 받아 버전이 높으면 교체

## 노선 데이터 갱신
```
git clone --depth 1 https://github.com/wantaekchoi/seoul-subway /tmp/seoul-subway
python3 tools/build_data.py /tmp/seoul-subway
git add data/subway_data.json && git commit -m "노선 데이터 갱신" && git push
```
앱 기본 데이터도 바꾸려면 `data/subway_data.json` 을 `app/src/main/assets/` 에 복사 후 빌드.

## 데이터 출처
- 역·노선: OpenStreetMap 기여자 (ODbL), Wikidata (CC0) — wantaekchoi/seoul-subway (MIT) 가공본
- 1~9호선 역간 시간: 서울 열린데이터광장 열차 시간표 (공공누리 1유형)
- 그 외 노선 역간 시간은 거리 기반 추정, 환승은 일괄 6분(도보+대기)

## 빌드
Android SDK 35, JDK 17+ → `./gradlew assembleDebug`
