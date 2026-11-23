# 쌤톡스 화면 디자인 규칙

2026-11-23 정리. 외부 스킬은 설치하지 않고 규칙만 가져왔다:
[mobile-android-design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-android-design)(MIT)·
[android-ux](https://github.com/rcosteira79/android-skills/tree/main/plugins/android-skills/skills/android-ux)(MIT)의 Material 3 점검표.
값은 모두 `Theme.kt` 한 곳에 있고, 화면에서는 이름으로만 쓴다.

## 색
- `MaterialTheme.colorScheme` 의 역할로만 쓴다(화면에 `Color(0x…)` 직접 쓰지 않기). 브랜드 색은 `Ssam`(칠판 그린·분필 크림·새싹·볼터치).
- 폰 배경화면 색을 따르는 동적 색은 쓰지 않는다 — 쌤톡스 브랜드 색을 지킨다.
- 진한 요약 카드(`primaryContainer` 칠판 그린) 위 강조색은 `Ssam.Sprout`, 흰 카드 위는 `primary`.
- 옅은 글(시각·건수·안내)은 `LocalContentColor` 의 70% 안팎, 또는 `onSurfaceVariant`.
- 구분선: 꾸밈용은 `outlineVariant`, 눌러야 하는 테두리는 `outline`.

## 글자
- `MaterialTheme.typography` 만 쓴다(`fontSize` 직접 쓰지 않기). 굵기도 단계에 들어 있으니 `fontWeight` 를 덧붙이지 않는다(이름 강조 같은 글 속 일부만 예외).
- 화면 큰 제목 `headlineMedium` · 카드 제목 `titleLarge`/`titleMedium` · 칸 제목 `titleSmall` · 본문 `bodyMedium`(긴 글 `bodyLarge`) · 설명 `bodySmall` · 단추·사람 이름 줄 `labelLarge` · 시각·건수 `labelMedium`.

## 간격·모서리
- 간격은 `Space` 의 4·8·12·16·20(카드 안쪽)·24·32 만. 10·14·6 같은 값은 쓰지 않는다.
- 모서리는 `MaterialTheme.shapes`(칩 8 · 작은 칸 12 · 카드 20 · 창 24~28). `RoundedCornerShape(n.dp)` 를 화면에 쓰지 않는다.
- 카드는 그림자 대신 흰 종이 + 옅은 테두리 한 줄(`Card` — Theme.kt).

## 요약 글
- 요약은 글 한 덩어리로 저장된다(알림·위젯·하루 정리가 같은 글). 앱 화면에서는 `SummaryText` 가 줄의 꼴을 보고 칸 제목·사람·시각·내용으로 나눠 그린다. 새 칸을 만들면 '이모지 제목 (건수)' 와 '- 누구 · 시각: 내용' 꼴을 지킨다.

## 접근성
- 누르는 칸은 48dp 이상. 칸 제목에는 `semantics { heading() }`, 한 덩어리로 읽어야 하는 줄은 `semantics(mergeDescendants = true)`.
- 밝은·어두운 모드 둘 다 확인. 글자 대비 4.5:1(본문)·3:1(큰 글·단추).
- 움직임은 `Motion`(Screens.kt) 하나로 — 폰의 '애니메이션 끄기'를 따르게 Compose 기본 시간 배율을 그대로 둔다.

## 고치기 전 점검(android-ux 의 Material 3 점검표에서)
```
rg 'Color\(0x[0-9a-fA-F]{6,8}\)' app/src/main --glob '!Theme.kt'   # 직접 쓴 색
rg 'RoundedCornerShape\(\s*\d+(\.\d+)?\s*\.dp\s*\)' app/src/main --glob '!Theme.kt'   # 직접 쓴 모서리
rg 'fontSize\s*=' app/src/main --glob '!Theme.kt' --glob '!SummaryWidget.kt'   # 직접 쓴 글자 크기(위젯은 Glance 라 예외)
rg 'import androidx\.compose\.material\.' app/src/main                 # Material 2 섞임
```

## 점검표 점수 (2026-11-23, android-ux 의 Material 3 점검 10항목)

| # | 항목 | 점수 | 메모 |
|---|---|---|---|
| 1 | 색 | 통과 | 화면 직접 색 0. 예외: 검색어 노란 표시, 알림 아이콘 색(브랜드 그린) |
| 2 | 글자 | 통과 | 화면에서 굵기 덧붙이던 55곳 → 글자 단계로. 위젯은 Glance 라 sp 직접(예외) |
| 3 | 모서리 | 통과 | 화면 직접 모서리 0(로고만 비율) |
| 4 | 높이·바탕 | 통과 | 그림자 대신 흰 종이 + 옅은 테두리 |
| 5 | 부품 | 통과 | Material 3 만(Material 2 섞임 0) |
| 6 | 배치·간격 | 일부 | 간격 10·14·6·2 → 단계 값(56줄). **남음: 폴드 펼친 화면·태블릿에서 글 폭 제한·두 칸 배치 없음** |
| 7 | 내비게이션 | 통과 | 아래 막대 네 칸, 아이콘+이름, 고른 칸 표시 |
| 8 | 움직임 | 일부 | 한 곳(Motion)에서 관리, 폰의 애니메이션 끄기 따름. M3 시간 단계(medium 등)로는 아직 아님 |
| 9 | 접근성 | 일부 | 칸·카드 제목 26곳에 '제목' 표시, 요약 카드 머리 한 덩어리로 읽기. **남음: 이모지 아이콘 설명, 대비 측정** |
| 10 | 테마 | 통과 | 앱 하나의 테마, 위젯도 같은 색(이전엔 안드로이드 기본 보라) |
