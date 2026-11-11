# 휴대폰 자비스 (MVP v0.1)

휴대폰에 온 알림을 모아 두었다가, 폰 안에서 도는 Gemma 4 E4B로 "오늘 알림 브리핑"을 만들어 주는 앱.
인터넷 권한이 없어서 데이터가 폰 밖으로 나가지 않습니다.

## 설치 순서 (Galaxy Z Fold4 기준)

### 1. PC 준비
1. Android Studio 설치: https://developer.android.com/studio
2. 이 폴더(`jarvis`)를 압축 해제 → Android Studio → **Open** → `jarvis` 폴더 선택
3. 처음 열면 Gradle 동기화로 라이브러리를 내려받습니다 (5~10분). 아래 "Sync" 진행 표시가 끝날 때까지 대기
   - AGP 업그레이드 권유 창이 뜨면 **나중에(Don't remind / Cancel)** 를 눌러도 됩니다

### 2. 폰 준비
1. 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드번호 7번 탭** → 개발자 모드 켜짐
2. 설정 → 개발자 옵션 → **USB 디버깅** 켜기
3. USB로 PC 연결 → 폰에 뜨는 "USB 디버깅 허용" → 허용

### 3. 앱 설치
Android Studio 상단에서 기기(SM-F936N 등)가 선택된 것 확인 → ▶ **Run** 버튼

### 4. 모델 받기 (폰에서)
1. 폰 브라우저로 https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm → **Files** 탭
2. `gemma-4-E4B-it.litertlm` (약 3.7GB) 다운로드 — 와이파이에서
3. 자비스 앱 → ② **파일 선택** → 다운로드 폴더에서 그 파일 선택 → 복사 완료까지 대기 (2~3분)
   - 복사가 끝나면 다운로드 폴더의 원본은 지워도 됩니다

### 5. 알림 권한
1. 자비스 앱 → ① **설정 열기** → 휴대폰 자비스 켜기
2. **"제한된 설정" 때문에 회색으로 잠겨 있으면**: 설정 → 애플리케이션 → 휴대폰 자비스 → 오른쪽 위 ⋮ → **제한된 설정 허용** → 다시 1번

### 6. 사용
권한을 켠 **이후에** 오는 알림부터 수집됩니다. 알림이 좀 쌓이면 "오늘 알림 요약하기"를 누르세요.

## 문제 해결
| 증상 | 해결 |
|---|---|
| 요약 중 token/context 관련 오류 | `Summarizer.kt`의 `CHUNK_CHARS`를 1000으로 낮추기 |
| GPU 실패 후 CPU로 느리게 동작 | 정상 동작(폴백). 상태 문구로 확인 가능 |
| 알림이 수집 안 됨 | 설정 → 배터리 → 백그라운드 사용 제한 → 자비스를 "절전 예외 앱"에 추가 |
| 특정 앱 알림을 빼고 싶음 | `NotificationCollectorService.kt`의 `IGNORED_PACKAGES`에 패키지명 추가 |
| `litertlm-android:latest.release` 동기화 실패 | Maven에서 최신 버전 번호를 확인해 고정 버전으로 교체 |

## 파일 구조
- `NotificationCollectorService.kt` — 알림 수집 (광고성 상시알림·묶음요약 제외)
- `NotificationDb.kt` — SQLite 저장, 7일 지나면 삭제
- `Summarizer.kt` — 규칙 전처리 → Gemma 부분 요약 → 최종 브리핑
- `MainActivity.kt` — 화면 (권한·모델 상태, 요약 버튼, 수집 목록)

## 다음 단계 (계획 2단계)
WorkManager로 아침·저녁 자동 브리핑 + 결과를 알림으로 받기

## 모델 메모
- 기본: Gemma 4 E4B (3.65GB) + MTP 가속 켜짐
- 너무 느리거나 폰이 뜨거우면: `gemma-4-E2B-it.litertlm`(2.58GB)을 받아 앱에서 "다시 선택"만 하면 교체됩니다 (코드 수정 불필요)
- 2026년 5월 5일 이전에 받은 모델 파일은 MTP를 쓰려면 다시 받아야 합니다
