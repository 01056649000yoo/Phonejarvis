package com.jarvis.phone

import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

/** 이번 버전에서 바뀐 점 한 줄 (버전 올릴 때 같이 고치기). 새 것을 앞에 — 앞 세 가지가 릴리스·안내 페이지의 '바뀐 점'으로도 나감(build.yml) */
private const val WHATS_NEW = "밤에 폰을 안 쓸 때 저절로 다듬고 호칭도 추천 · 쓸수록 나에게 한 말을 더 잘 알아봄(하루 정리 때 저절로 다듬음) · 나에게 한 말을 더 잘 알아봄 · 요약에서 방 이름을 눌러 바로잡기 · 설치할 때 나를 부르는 말을 꼭 넣게 · 꼭 볼 것만: 나를 부른 말·1:1·꼭 받을 사람·키워드 · 설정을 간단하게 · 놓치기 싫은 방은 앞 요약과 이어서 풀어 쓴 요약으로 · 놓치기 싫은 방을 설정에서 고르기 · 알림이 몰려도 화면이 덜 끊기게 · 정기 요약은 나와 관련된 것만 · 두세 마디 방은 최근 말 하나만 · 처음 설치할 때 모아서 받을지 물어봄 · 답할 것에는 나를 부른 말과 1:1만 · 하루 정리가 더 짧게 · 놓치던 알림·빠지던 백업 고침 · 앱 아이콘이 엇갈리던 것 고침 · 설정 백업 매일 자동 · 알림 앱 정리 화면 정리 · 받아 둔 모델 자동 찾기 · 설치 방법 안내 · 요약 탭에 최근 요약이 늘 보이게 · 할 일·마감 카드 없앰 · 나와 관련된 것만 요약 · 버전·업데이트 일시 표시 · 하루 정리는 하루 종일 온 알림을 총정리 · 빠지던 알림 없게 · 카톡 단톡방은 원문 줄로 · 이어진 대화 ↪ · 답한 물음 내리기 · 메일은 제목 그대로 · 짧으면 원문 그대로 · 오해 없는 요약 · 어제 메시지가 섞이지 않게 · 오픈채팅 한 방으로 · 내가 만든 묶음별 연락 · 모든 연락을 간단한 내용까지 · 중요한 대화만 요약해요 · 캘린더는 일정 그대로 · 요약 문장 다듬기"

/** 업데이트 내역 큰 창 (버전 올릴 때 같이 고치기) */
private val WHATS_NEW_DETAIL = listOf(
    "🧭 쓸수록 정확해져요" to "나에게 한 말인지를 단서마다 점수를 줘서 판정해요(내 말에 이어진 답, 나를 부른 사람이 이어서 한 말, 묻는 말, 작은 방, 내가 자주 답하는 사람). 하루 정리 때마다 그날 대화로 이 점수를 저절로 다듬어요 — 내가 답한 말은 '나에게 한 말'로 배우고, 애매한 말은 폰 안의 AI가 예/아니오로 판단해요. 이름·멘션은 늘 잡아요. 설정 → 나를 부르는 말에서 지금 상태를 볼 수 있어요.",
    "🧭 나에게 한 말을 더 잘 알아봐요" to "내 말과 내용이 겹치는 답은 몇 마디 뒤에 와도 나에게 한 말로 봐요. 나를 부른 사람이 이어서 한 말(10분 안, 세 마디까지)도 함께 보여 줘요. '유쌤' 같은 두 글자 이름이 '자유쌤'처럼 다른 말 안에서 잘못 잡히던 것도 고쳤어요.",
    "🔧 요약에서 바로잡기" to "요약의 방 이름(배지, '그 밖의 방'에 적힌 이름)을 누르면 그 자리에서 📌 놓치기 싫은 방으로 올리거나, 나와 상관없는 방은 건수만 보게 내릴 수 있어요.",
    "🎯 꼭 볼 것만 모아 보는 앱으로" to "쌤톡스가 보여 주는 것을 넷으로 정했어요: 나를 부르거나 멘션한 말, 나에게 온 1:1, 꼭 받을 사람(지인), 키워드. 단톡방은 나를 언급한 말만 요약하고, 나머지는 건수만 적어요. 예전의 '내가 속한 방 한 줄'은 없앴어요 — 나를 안 불러도 꼭 볼 방은 '놓치기 싫은 방'에 넣으면 돼요.",
    "🧹 설정을 간단하게" to "설정 첫 화면에 '쌤톡스가 보여 주는 것' 네 가지(나를 부르는 말·꼭 받을 사람·키워드·놓치기 싫은 방)를 모았어요. 방마다 '저절로·늘 중요·한 줄·건수만'을 고르던 것은 '놓치기 싫은 방' 하나로, '중요한 대화만 요약' 스위치는 없앴어요(늘 켬).",
    "📌 놓치기 싫은 방" to "설정에 '놓치기 싫은 방'이 생겼어요. 여기 넣은 방은 나를 부르지 않아도 정한 시각 요약과 하루 정리에 늘 내용까지 요약해요. 읽기만 하는 동학년방·공지방을 넣어 두세요. 이 방들은 원문 줄을 그대로 보여 주지 않고 AI가 요점을 풀어 써요. 앞서 그 방을 요약한 내용과 비교해서, 이어진 이야기면 무엇이 정해졌고 달라졌는지를 중심으로 정리해요.",
    "🪶 더 가볍게" to "알림을 모으는 일을 화면과 다른 줄에서 하고, 모으는 규칙은 설정이 바뀔 때만 다시 읽어요. 단톡방 알림이 몰려도 앱 화면이 덜 끊겨요. 앱 목록과 통계는 알림 앱 정리·키워드 화면을 열 때만 읽어서 첫 화면도 가벼워졌어요.",
    "🗞 정기 요약은 나와 관련된 것만" to "정한 시각에 오는 요약과 하루 정리에는 답할 것·나에게 온 대화·사람 묶음·업무만 보여 줘요. 내가 속한 방은 줄을 쓰지 않고 곳 수와 건수만 적어요(AI도 쓰지 않아 폰도 가벼워져요). 직접 누른 '지금 정리'와 위젯 요약에서는 내가 속한 방도 한 줄씩 볼 수 있어요.",
    "✂️ 두세 마디 방은 최근 말 하나만" to "내가 속한 방에 온 두세 마디를 ' / '로 이어 붙여 보여 주던 것을, 가장 최근 말 하나만 짧게 보여 주도록 바꿨어요.",
    "🎯 익히는 기간 없앰" to "처음 14일 동안 작은 단톡방을 모두 '내가 속한 방'으로 보던 것을 없앴어요. 한 번 말한 방은 바로 내가 속한 방이 되고, 놓치기 싫은 방은 방 고르기에서 '늘 중요'·'한 줄'로 정하면 돼요.",
    "🙋 답할 것에는 나를 부른 말만" to "단톡방에서는 나를 부르거나 멘션하며 묻는 말만 답할 것에 올라가요(학부모 방도 같아요). 1:1은 그대로 올라가고, 처음 보는 사람의 카톡도 '선생님/쌤'하고 부르며 물으면 올라가요. 나를 부르지 않은 물음은 '나에게 온 대화'에 내용이 그대로 보여요.",
    "🗓 하루 정리가 더 짧게" to "큰 단톡방에서 한마디 했을 뿐인데 그 방의 하루 잡담이 '나에게 온 대화'를 차지하던 것을 고쳤어요 — 나에게 온 구간만 보여 줘요. 멘션 방의 답할 것은 나에게 한 말만, '그 밖의 방'은 건수 많은 곳 이름만 적어요.",
    "📥 놓치던 알림을 모아요" to "매일 같은 글로 오는 알림(새 알림장 등록 등)이 첫날만 모이던 것, 모으지도 않은 알림을 상태바에서 지우던 것을 고쳤어요. '비밀번호'라는 말만 있어도 버리던 것은 실제 값이 있을 때만 거르고, 앱 업데이트·재시작으로 끊긴 동안 온 알림도 다시 모아요.",
    "💾 백업에 할 일도" to "직접 넣은 할 일이 백업에 들어가요. 백업 비밀번호를 정하거나 바꾸면 바로 백업하고, 불러온 뒤에는 화면도 불러온 설정으로 바뀌어요.",
    "🌱 알림 앱 정리 고침" to "'추천대로 적용'이 직접 켜고 끈 앱을 덮어쓰지 않아요. 알림이 오는데 '7일간 0건' 경고가 뜨던 것, 뺀 방이 방 고르기 목록에 없어 다시 켤 수 없던 것도 고쳤어요.",
    "🖼 앱 아이콘이 제자리에" to "알림 앱 정리에서 묶음을 바꾸거나 추천을 적용해 순서가 바뀌면, 앱 이름 옆에 앞에 있던 다른 앱의 아이콘이 남아 엇갈렸어요. 이제 앱마다 제 아이콘이 보여요.",
    "🌱 알림 앱 정리가 깔끔해졌어요" to "메시지·SNS / 업무·일정 / 그 밖의 앱을 칩으로 골라 한 묶음씩 봐요. 알림이 안 들어오는 앱은 따로 칸을 두지 않고 그 앱 줄 안에 경고와 '알림 설정 열기'가 보여서, 같은 앱이 두 번 나오지 않아요. 앱 추가·광고 거르기·도움말은 목록 아래로 옮겼어요.",
    "💾 설정 백업이 매일 자동으로" to "예전엔 앱을 열거나 정한 시각 요약이 돌 때만 백업했어요. 이제 매일 새벽에 따로 백업해서, 앱을 안 연 날에도 다운로드/쌤톡스 폴더에 하루 한 번 저장돼요. 나를 부르는 말·방마다 정한 모양·내가 말한 방 기록도 함께 들어가요. 백업 비밀번호를 정해 둬야 저장돼요(설정 → 백업·복원).",
    "🔎 받아 둔 모델 자동 찾기" to "AI 모델 받기 화면에서 '받아 둔 모델 자동으로 찾기'를 누르면 다운로드·문서 폴더에서 모델 파일(.litertlm)을 찾아 바로 가져와요. 처음 한 번 '모든 파일 접근'을 켜 달라고 해요(모델 파일을 읽는 데만 써요). 켜기 싫으면 예전처럼 파일 선택으로 골라도 돼요.",
    "📖 설치 방법 안내" to "설정에 '설치 방법 보기'와 '동료에게 설치 링크 보내기'가 생겼어요. Play 프로텍트 '앱 차단됨'이 떴을 때 푸는 법, 알림 접근이 회색으로 잠겼을 때 푸는 법을 차례대로 보여 주는 페이지예요.",
    "📦 패키지 이름은 그대로" to "v1.15.0에서 앱 이름표(패키지 이름)를 바꿨다가 되돌렸어요 — 새 이름은 폰이 처음 보는 앱이라 Play 프로텍트가 설치를 막았어요. 지금처럼 같은 링크로 덮어 업데이트하면 돼요. 혹시 v1.15.0을 따로 설치했다면 쌤톡스가 두 개 보이는데, 요약 탭 안내대로 다른 하나를 지워 주세요.",
    "📋 요약 탭이 비지 않게" to "요약 탭에 가장 최근 요약과 오늘의 하루 정리를 따로 보여 줘요. 예전엔 가장 최근 것 하나만 보여 줘서, 그게 숨긴 하루 정리면 방금 한 요약이 있어도 요약 탭이 텅 비었어요. 숨긴 요약은 '다시 보기' 한 줄로 남아요. 할 일·마감 카드와 하루 정리의 마감 칸은 없앴어요(할 일인지 아닌지 구분이 어려워서).",
    "🎯 나와 관련된 것만 요약" to "방마다 나와의 거리를 셋으로 나눠요. 나에게 온 대화(1:1로 아는 사람·문자·학부모, 나를 부르거나 멘션한 말이 있는 방)는 내용까지 요약하고, 내가 말해 본 단톡방은 한 줄로, 오픈채팅·커뮤니티처럼 그 밖의 방은 이름과 건수만 적어요(그 방에는 AI를 쓰지 않아 요약도 빨라져요). 큰 단톡방에서 누가 나를 부르거나 내 말에 이어서 말하면, 방 전체를 요약하지 않고 그 말과 앞뒤 한 마디씩만 원문 그대로 보여 줘요. 🙋 답할 것은 나에게 한 물음만 — '관심 있으신 선생님들 참여 부탁'처럼 여러 사람에게 돌린 말은 빼요. 나를 부르는 말(이름·별명·닉네임)은 설정 → 사람 묶음 → 🙋 나를 부르는 말에 넣어 주세요(카톡에 찍힌 내 이름을 추천해요). 아직 안 넣었거나 처음 2주 동안은 단톡방을 함부로 줄이지 않아요. 방이 잘못 나뉘었으면 설정 → 방 고르기에서 방마다 '늘 중요 · 한 줄 · 건수만'을 고를 수 있어요.",
    "🏷 버전과 업데이트 일시" to "설정 맨 위와 이 업데이트 안내에 지금 버전과 그 버전이 나온 일시를 보여 줘요. 안내 페이지(받기 링크)에도 지금 받는 버전과 업데이트 일시가 보여서, 폰에 깔린 버전이 최신인지 바로 견줘 볼 수 있어요.",
    "🗓 하루 정리는 총정리로" to "하루에 알림이 수백 개 와도 하루 정리가 15줄로 줄어들지 않아요. 맨 위 ✨ 오늘의 핵심(AI 5줄)과 📅 내일, 그 아래 🙋 답할 것은 상한 없이 모두, 💼 업무·👥 묶음, 💬 방별 하루(내용 있는 방은 모두 한 줄씩, 인사·맞장구뿐인 방은 이름과 건수만), 📦 앱 알림 건수까지 이어져요. 방 줄 아래 '▸ 구간별 요약'을 누르면 그 방의 요약을 시각별로 펼쳐 볼 수 있어요. 알림창에는 핵심과 답할 것만 보이고, 누르면 전체가 열려요.",
    "🕳 빠지던 알림 없게" to "문자·카톡은 보낸 시각으로 저장되는데, 그 시각이 폰에 도착한 때보다 조금 이를 수 있어요. 정각 5분 전 미리 준비처럼 그 사이에 요약이 돌면 그 메시지가 어느 요약에도 안 들어갔어요(예: 9시 55분 문자). 이제 요약마다 어디까지 맡았는지를 알림 번호로 기억해, 늦게 저장된 알림도 다음 요약에 꼭 들어가요.",
    "🧵 카톡 단톡방은 원문 줄로" to "카톡은 짧은 말·물음·토막이 많아 AI가 바꿔 쓰면 뜻이 틀어졌어요. 이제 여러 사람이 말한 단톡방은 AI가 핵심 줄을 고르기만 하고, 화면에는 그 줄의 원래 말을 보여 줘요. 한 사람이 잇따라 보낸 토막 말은 '/'로 이어 한 줄로 봐요.",
    "↪ 이어진 대화" to "지난 요약 끝(30분 안)에서 이어진 대화는 그 앞 말을 AI에게 참고로 주고, 줄 끝에 '↪ 앞: …'으로 앞의 마지막 말을 같이 보여 줘요.",
    "✔ 답한 물음은 내려요" to "🙋 물음이 올라온 방에 내가 답하면(알림에 딸려 오는 내 답으로 알아봐요) 하루 정리에서 답할 연락으로 남기지 않고 '✔ 답함'으로 적어요. 답한 뒤 아무도 말하지 않으면 알아보지 못할 수 있어요.",
    "🧵 단톡방 대화는 흐름 그대로" to "지인이 단톡방에서 한 말을 사람별로 떼어 모으니 대화 흐름이 사라져 요약이 엉뚱하게 이어졌어요. 이제 단톡방 말은 그 방 대화 요약에 그대로 두고, 👥 지인 칸에는 1:1로 온 연락만 모아요.",
    "📧 메일은 제목 그대로" to "💼 메일은 AI가 여러 통을 한 문장으로 엮지 않고 '보낸 사람: 제목'을 최근 4통까지 그대로 보여 줘요. 요약 끝에 붙는 원래 물음은 따옴표로 묶어 '\"…\"라고 물음'처럼 원문임을 드러내요.",
    "🔍 짧으면 원문 그대로" to "한 사람이 보낸 말이 맞장구를 빼고 합쳐 80자 이하면, AI가 바꿔 쓰지 않고 원래 메시지를 '/'로 이어서 그대로 보여 줘요. 짧은 말을 AI가 해석하다 뜻이 달라지던 것('해양교육 많네' → '불만이 많다')을 막아요. 여러 사람이 섞인 단톡방과 긴 대화는 지금처럼 AI가 요약해요.",
    "🔍 오해 없는 요약" to "AI가 메시지에 없는 감정(짜증·걱정·부끄러움)을 붙이거나, 서로 다른 이야기를 '…하며 …했으나'로 엮어 뜻이 달라지던 것을 막았어요. 요약에 원문에 없는 감정이 들어가면 요약 대신 원래 메시지를 짧게 보여 줘요. '김진수 지인이 …'처럼 묶음 이름이 붙거나 '아녜요?.'처럼 문장부호가 겹치던 것도 고쳤어요.",
    "📜 어제 메시지가 섞이지 않게" to "카톡 알림에는 그 방의 지난 메시지(어제 것까지)가 딸려 와요. 이미 모은 메시지인데 지금 온 것처럼 다시 들어가 요약에 어제 이야기가 섞이던 것을 고쳤어요. '급락..?' 같은 혼잣말은 요청으로 붙이지 않고, 긴 인터넷 주소는 (링크)로 줄여요. 설정 → 시스템에 🔧 카톡 알림 구조(문제 찾기용)를 넣었어요.",
    "💬 오픈채팅은 한 방으로" to "오픈채팅 메시지가 보낸 사람마다 따로 1:1 대화처럼 나뉘어 방이 13개씩 나오던 것을 고쳤어요. 이제 방 이름으로 한데 모아 한 줄로 정리해요(요약도 빨라져요). '말씀하심'처럼 높임말로 줄이던 것은 '말함'으로 맞추고, '아 그래요?' 같은 맞장구는 요청으로 붙이지 않아요. 묶음 사람이 나에게 부탁한 말은 🙋 표시와 함께 짧게 적어요.",
    "👥 내가 만든 묶음별 연락" to "요약 탭의 '학부모 연락' 고정 칸을 없앴어요. 사람들 탭에서 만든 묶음(교직원·가족·지인…)마다 카드가 하나씩 생겨, 오늘 그 사람들이 보낸 연락을 사람별로 보여 줘요. 예전에 학부모 명부에 넣어 둔 이름은 🤝 지인 묶음으로 옮겼어요. 이름·이모지는 사람들 탭에서 바꿀 수 있어요.",
    "💬 모든 연락을 간단한 내용까지" to "이름만 보고는 결국 폰을 열게 되니까, 온 연락은 내용을 짧게라도 보여 줘요. 몇 마디뿐인 방은 AI 없이 그 말을 그대로 짧게, 많으면 AI가 핵심 한 줄로 정리해요. 나에게 직접 온 1:1 메시지는 물음이 없어도 요약해요. 인사·맞장구(ㅋㅋ, 네 감사합니다)뿐인 방만 '그 밖의 방'에 이름을 적어요.",
    "🎯 중요한 대화만 요약해요" to "쌤톡스는 '폰을 안 봐도 놓치는 연락이 없게' 하는 선생님의 연락 비서예요. 그래서 명부·키워드·사람 묶음·업무 앱, '선생님'을 부르는 대화, 1:1 연락, 기한이 있는 단톡방 부탁은 AI가 꼼꼼히 요약하고, 몇 마디뿐인 잡담 방은 AI 없이 그 말을 짧게 보여 줘요. 설정 → 모아서 알림의 '🎯 중요한 대화만 요약'을 끄면 예전처럼 모든 방을 요약해요.",
    "📅 캘린더는 일정 그대로" to "캘린더 알림은 AI가 대화처럼 요약하지 않고 '모두모임 (내일 오전 9:00), …'처럼 일정 이름과 시각을 그대로 보여 줘요.",
    "✏️ 요약 문장 다듬기" to "줄 앞에 이름이 있으니 내용에서 그 이름을 다시 말하지 않아요. '언급함' 대신 '말함'으로, '??'처럼 글자 없는 물음은 요청으로 붙이지 않아요. '감사합니다 선배님'처럼 인사만 있는 방은 맨 아래 '그 밖의 방'에 이름만 적어요.",
    "📥 빠지던 메시지를 모아요" to "카톡·문자가 한꺼번에 여러 개 오면 마지막 것만 남던 것을 고쳐 모두 모아요. 같은 사람이 같은 말을 다시 보내도 모아요. '확인 부탁드려요 15000원'처럼 금액·전화번호가 든 메시지를 인증번호로 잘못 보고 버리던 것도 고쳤어요.",
    "🧒 학생 이름을 더 정확히" to "성을 뺀 이름은 이름으로 쓰였을 때만 찾아요('지우가'·'지우 어머니'는 맞고 '지우개'는 아님). '하나'·'보람'처럼 흔한 낱말과 같은 이름은 성까지 쓴 이름으로만 찾아요.",
    "🗓 하루 정리를 더 깔끔하게" to "한 사람은 한 칸에만, 지난 일정은 '있었던 일'에 시각 순으로, 은행·카드·Google 같은 업체 알림은 빼요. 사람 묶음의 같은 사람이 여러 번 쌓이지 않고, 월요일처럼 주말부터 모았으면 언제부터인지 알려 줘요. 그 시각에 다른 요약이 실패해도 하루 정리는 와요.",
    "💼 업무 메일은 정기 알림으로 빼지 않아요" to "매일 비슷한 시각에 오는 업무 앱 알림(동료의 메일 등)을 정기 알림으로 짐작해 건수로만 남기던 것을 고쳤어요. 직접 '요약에서 빼기'한 것은 그대로 빠져요.",
    "🏠 위젯을 누르면 바로 요약" to "위젯의 요약 글을 누르면 앱에서 마지막에 보던 화면 대신 📋 요약 탭 맨 위의 최신 요약이 펼쳐진 채로 열려요.",
    "🧹 요약 칸을 줄였어요" to "요약은 이제 다섯 칸이에요: 👥 내가 만든 사람 묶음(가족·학부모·교직원 등, 묶음마다) → ⭐ 중요 키워드 → 💼 업무·일정 → 💬 대화 요약 → 앱·정기 알림 건수. 한 사람은 한 묶음에만 들어가고, 묶음에 들어간 연락은 대화 요약에 다시 나오지 않아요. 👪 학부모 전용 칸은 없애고 학부모 명부도 다른 묶음과 똑같이 보여요.",
    "🙋 답장할 연락은 대화 요약 안에" to "답장할 연락을 따로 칸으로 두지 않고, 💬 대화 요약 맨 위 줄에 🙋 표시로 보여 줘요.",
    "📌 마감 자동 등록은 없앴어요" to "알림에서 '금요일까지'·'3시' 같은 말을 찾아 할 일로 넣던 기능은 엉뚱한 것이 자주 들어가서 없앴어요. 직접 넣은 할 일은 그대로예요.",
    "💼 업무·일정은 고른 앱만" to "설정 → 🌱 알림 앱 정리에서 '💼 업무·일정 앱으로 지정'한 앱의 알림만 업무·일정 칸에 모아, AI가 앱마다 요약해요. 처음에는 메일·캘린더·할 일처럼 이름으로 알아본 앱이 들어 있고, 빼거나 더할 수 있어요.",
    "⚡ 미리 요약은 글자 수로" to "쌓인 메시지가 AI 한 번에 읽을 만큼(약 1,500자) 넘으면 화면이 꺼진 동안 미리 정리해 둬요. 충전 중이 아니면 배터리 30% 이상일 때만, 화면을 켜면 멈춰요.",
    "📎 줄에 못 들어간 부탁 수" to "단톡방 줄에는 급한 부탁 3개까지만 보이고, 나머지는 '외 부탁 N건(세부 내역)'으로 개수를 알려 줘요. 이름 없이 온 부탁은 누가 말했는지 뚜렷할 때만 이름을 붙여요.",
    "🧾 요약이 더 읽기 쉽게" to "요약을 칸 제목·사람 이름·시각·내용으로 나눠 보여 줘요. 이름은 굵게, 시각과 건수는 옅게, 내용은 다음 줄에. 앱 전체의 글자·간격도 한 가지 규칙으로 정리했어요.",
    "💬 단톡방은 사람마다 부탁" to "큰 단톡방도 '이선생: 버스 인원 확인해 달라고 함, 박선생: …'처럼 누가 무엇을 부탁했는지 3개까지 이름을 붙여 보여 줘요. 이름이 실제로 그 말을 한 사람과 맞는지 앱이 한 번 더 확인해요.",
    "⏳ 알림이 많아도 끊기지 않게" to "중요한 방(학부모·키워드·내 묶음·1:1)부터 정리하고, 6분 안에 다 못 하면 지금까지 정리한 것을 먼저 알려 드린 뒤 남은 방을 이어서 정리해 같은 알림을 고쳐 써요.",
    "🏠 위젯도 쌤톡스 색" to "위젯이 안드로이드 기본 보라색 대신 앱과 같은 크림·칠판 그린 색으로 나와요. 위젯 요약도 이름·시각·내용이 나뉘어 보여요.",
    "🗣️ 한 사람 말은 한 줄로" to "같은 사람이 여러 번 보낸 메시지를 하나씩 늘어놓지 않고 핵심 한 줄로 정리해요. 부탁은 오늘·내일처럼 급한 것부터 2개까지. 한 마디뿐인 메시지도 '…하는지 물음'·'…달라고 함'처럼 짧게 바꿔요. AI가 원문에 없는 숫자를 쓰면 한 번 더 물어 바로잡아요.",
    "👥 내 묶음은 사람별로" to "가족·교직원 같은 👥 묶음 사람은 카톡·문자·단톡방을 가리지 않고 같은 이름끼리 모아, 이름은 그대로 두고 그 사람이 한 말만 한 문단으로 정리해요.",
    "⚡ 미리 요약" to "쌓인 메시지가 약 1,500자를 넘으면 화면이 꺼진 동안 대화방마다 미리 정리해 둬요(충전 중이 아니면 배터리 30% 이상일 때만). 요약 시각에는 그 뒤 온 것만 정리해서 훨씬 빨라요. 화면을 켜면 멈춰요.",
    "🚫 광고·정기 알림 거르기" to "이름(키워드)이 들어 있어도 광고는 걸러요. 대표번호(15xx·080·02 등) 문자는 📦 앱 알림으로, 'OO님'처럼 이름을 부르기만 한 알림은 ⭐ 키워드에서 빼요. 날씨·서버 알림처럼 매일 비슷한 시각에 오는 알림과 봇 알림은 끝에 🔁 건수 한 줄로만. 알림 탭에서 길게 누르면 '요약에서 빼기'도 돼요.",
    "🏠 위젯 바로 놓기" to "요약 탭·설정에서 '위젯 놓기'를 누르면 홈 화면에 바로 놓여요. 위젯에는 모인 알림 수 없이 요약(👥 내 묶음·💼 업무·일정·💬 대화 요약)만 보이고, 📋 요약을 누르면 그 자리에서 새로 정리해요.",
    "🔐 권한을 줄였어요" to "Play 스토어에 올릴 준비로 '모든 파일 접근' 권한을 뺐어요. 모델 파일과 백업 파일은 파일 고르기 창에서 한 번 골라요(다운로드 폴더가 바로 열려요). 하루 한 번 자동 백업은 권한 없이 그대로 다운로드/쌤톡스 폴더에 쌓여요. 정한 시각 알림은 '알람 및 리마인더'를 켜 두면 딱 맞춰 와요(설정 → 🔕 모아서 알림).",
    "🗓 하루 정리" to "매일 밤 10시(설정 → 🔕 모아서 알림에서 시각 바꾸기·끄기)에 그날 요약하며 쌓아 둔 방별 기록을 모아 하루 종일 온 알림을 총정리해 드려요. ✨ 오늘의 핵심 · 📅 내일 · 🙋 답할 것(모두) · 💼 업무 · 👥 묶음 · 💬 방별 하루 · 📦 앱 알림 건수 순서예요. 폰이 뜨겁거나 배터리가 적으면 핵심 줄 없이 방별로만 보내요.",
    "🔎 검색이 빨라졌어요" to "알림을 넣을 때 두 글자 조각 찾아보기(색인)를 같이 만들어, 3개월 치가 쌓여도 낱말 가운데 글자까지 금방 찾아요. 한 글자 낱말은 예전처럼 찾아요.",
    "📨 알림 원문 3개월까지" to "설정 → 🧹 저장 공간에서 받은 알림 원문을 3개월까지 남길 수 있어요(기본 7일). 오래된 메시지도 알림 탭 🔎 검색으로 찾을 수 있어요. 하루 1,000건이면 3개월에 25MB쯤 써요.",
    "🔎 알림 검색" to "알림 탭 맨 위 검색 칸에 낱말을 치면 모아 둔 알림 전체(오늘 것만이 아니라 저장 기간 안의 것 모두)에서 내용·보낸 사람·대화방 이름으로 찾아 최근 것부터 보여 줘요. 띄어 쓰면 낱말이 모두 든 것만. 찾은 낱말은 노랗게 표시돼요.",
    "🗣️ 요약 말투를 부드럽게" to "'다음에 연락해' 같은 말을 '지시'·'명령'·'통보'처럼 딱딱하게 적던 것을 '다음에 연락하라고 함'처럼 고쳤어요.",
    "🌡️ 폰에 무리가 덜 가게" to "폰이 뜨겁거나 배터리가 15% 이하(충전 중 아님)면 AI를 켜지 않고 대화방마다 최근 말 그대로 정리해요. 조금 따뜻하거나 절전 모드면 요약은 하되 문자 답장 초안은 다음으로 미뤄요. AI가 쓰는 메모리 공간도 실제로 쓰는 만큼만 잡고, GPU가 안 되는 폰에서는 CPU를 다 쓰지 않게 했어요. 요약 끝 ⏱ 줄에 메모리·발열도 나와요.",
    "🌱 설치 첫날부터 알림 앱 추천" to "알림 접근을 켜는 순간 알림창에 남아 있던 알림을 앱별로 세고, 처음 정리하기 전에는 폰에 설치된 메시지·업무·알림 앱도 이름을 보고 미리 추천해요(쇼핑·게임·금융 등은 빼기). 설정 → 🌱 알림 앱 정리에서 '추천대로 적용' 한 번이면 돼요. 알림창에 있던 알림은 요약에는 넣지 않아요.",
    "⚡ 요약이 더 빨라졌어요" to "AI에 넣는 글과 AI가 쓰는 답을 짧게 줄이고, 맞장구·잡담은 AI에 넣지 않아요. 문자 답장 초안은 요약 알림을 먼저 보낸 뒤에 써서 결과가 늦어지지 않아요.",
    "⏱ 걸린 시간이 보여요" to "요약 맨 아래와 📝 최근 자동 요약에 '⏱ 48초 · 모델 불러오기 21초(GPU) · AI 2번 27초'처럼 어디에 시간이 들었는지 나와요. 오래 걸리면 이 줄을 알려 주세요.",
    "✉️ 문자 답장 초안" to "문자로 온 답장할 연락은 요약할 때 AI가 답장 초안을 미리 써 둬요(한 번에 5건). 요약 탭 ✉️ 답장할 문자에서 고친 뒤 💬 보내기를 누르면 그 사람에게 바로 가요. 바로 보내기가 안 되면 복사하고 문자 앱을 열어 드려요. 자동으로 보내는 일은 없어요. (카톡은 카톡 안의 답장 추천을 쓰세요)",
    "🙋 답장할 연락은 명부에 있는 사람만" to "묻는 말이 있다고 다 올리지 않고, ⭐ 중요 키워드·학생 이름·👪 학부모 명부·👥 사람 묶음에 있는 이름이 나온 연락만 답장할 연락으로 올려요. 다른 사람의 묻는 말은 💬 메시지 칸에 '→ 요청'으로 그대로 보여요.",
    "📚 지난 요약을 날짜별로" to "지난 요약이 오늘·어제·날짜별 묶음으로 정리돼요. 오늘만 펼쳐 두고, 날짜를 누르면 그날 요약이 나와요. 요약은 30일, 원래 알림은 7일 지나면 저절로 지워져요(설정 → 🧹 저장 공간에서 바꾸기).",
    "⏰ 정한 시각에 요약이 꼭 와요" to "정한 시각 5분 전에 조용히 미리 요약해 두었다가 정각에 결과 알림만 보내요. 새 알림이 없어도 '새 알림 없음 · 마지막 요약 언제'를 알려 줘서 요약이 돌았는지 바로 알 수 있어요. (N시간마다 요약은 새 알림이 없으면 예전처럼 조용히 넘어가요)",
    "🚀 두 번째 요약부터 더 가볍게" to "AI 모델을 폰에 맞게 바꿔 둔 것(캐시)을 쌤톡스 전용 공간에 보관해요. 예전엔 폰이 저장공간을 정리할 때 지워져 매번 처음부터 다시 만드느라 느리고 뜨거워질 수 있었어요. 모델을 바꾸면 자동으로 비워요.",
    "🌅 아침 요약에 어젯밤 메시지도" to "예전엔 자동 요약이 '오늘 0시'부터만 모아서, 저녁 요약 뒤~자정에 온 메시지가 빠지고 새벽에 새 알림이 없으면 아침 요약이 조용히 넘어갔어요. 이제 지난 요약 바로 다음부터(최대 3일) 모두 요약해요.",
    "⏰ 자동 요약이 돌았는지 보여요" to "위젯 위쪽에 '⏰ 09:00 자동 · 새 알림 없음 · 다음 17:00'처럼 마지막 자동 요약 결과와 다음 시각이 보여요. 설정 → 🔕 모아서 알림 맨 아래 📝 최근 자동 요약에서 지난 기록도 볼 수 있어요.",
    "🔐 인증번호는 모으지 않아요" to "은행·본인확인 인증번호 알림은 상태바에 그대로 두고, 쌤톡스에는 저장하지 않아요. 요약·위젯·알림 탭에도 나오지 않아요.",
    "🔒 백업 비밀번호를 더 튼튼하게" to "새로 정하는 백업 비밀번호는 6자 이상이고 글자도 섞을 수 있어요. 예전 4~5자리 비밀번호는 그대로 쓰이지만 바꿔 달라고 알려 드려요. 예전에 만든 백업 파일도 그대로 열려요.",
    "📵 새 폰 옮기기에서 빠져요" to "폰을 바꿀 때 자동으로 옮겨지는 데이터에 알림 내용·학생 기록이 들어가지 않게 했어요. 설정은 💾 백업(암호화)으로 옮겨요.",
    "👀 글자가 또렷하게" to "사람 묶음·학부모 명부의 이름 넣는 칸과 🌱 알림 앱 정리가 진한 초록 바탕이라 입력한 글자와 '최근 연락한 사람' 칩이 잘 안 보였어요. 흰 카드로 바꿔 또렷하게 보여요.",
    "📞 전화는 늘 그대로" to "🔇 소리도 끄기를 켜도 전화는 쌤톡스가 손대지 않아요. 누가 걸어도 평소처럼 울리고, 끄는 것은 메시지·알림 소리뿐이에요. 예전에 만든 '쌤톡스 모아 두기' 모드는 저절로 새로 바뀌어요.",
    "🐢 하단 메뉴가 멈추던 것" to "앱을 열 때마다 알림 앱 추천을 하려고 큰 AI 모델을 불러오느라 화면이 버벅였어요. 이제 AI 추천은 설정 → 🌱 알림 앱 정리를 열었을 때만, 정리 중이 아닐 때만 해요. 앱 아이콘은 한 번 읽으면 모아 두고, 탭은 더 가볍게 바뀌어요.",
    "✨ 더 깔끔한 화면" to "카드를 흰 종이처럼 또렷하게, 하단 메뉴의 고른 탭은 새싹 연두로 바꿨어요. 가장 많이 쓰는 '지금 정리하기' 버튼은 요약 탭 맨 위로 올렸고, 알림 탭의 아직 요약 안 한 알림은 최근 3건만 보이게 접어 두었어요(더 보기로 펼침).",
    "🔕 모아서 알림 하나로" to "설정이 너무 많아 단순하게 바꿨어요. 설정 → 🔕 모아서 알림을 켜면 모으는 앱 알림은 늘 도착하자마자 숨기고 모아 두었다가, 정한 시각·몇 시간마다 요약해서 알려 줘요. 끄면 알림은 평소처럼 오고 요약은 직접 눌러요. 수업 시간표·퇴근 모드는 없앴어요.",
    "📚 알림이 많아도 빠짐없이" to "예전에는 대화방이 많으면 최근 10곳만 요약했어요. 이제 모든 대화방을 요약하고, 메시지가 30개가 넘는 방은 30개씩 나눠 요약한 뒤 합쳐요.",
    "🏠 위젯이 최신 요약으로 바뀌어요" to "위젯에서 📋 요약을 눌러 정리가 끝나도 예전 요약이 그대로 보이던 것을 고쳤어요. 이제 요약이 끝나면 위젯이 바로 새 요약으로 바뀌어요.",
    "🙈 카톡 묶음 알림도 숨겨요" to "카톡의 '새 메시지 N개' 묶음 알림이 상태바에 남던 것을 고쳤어요. 모아 두는 시간이 시작되면 그 전에 와 있던 모으는 앱 알림도 정리해요. 설정 → 🔕 모아 두기·요약 시간에서 숨기는 때를 '모아 두는 시간에만 / 언제나 / 숨기지 않음' 중에 골라요.",
    "🔕 모아 두기·요약 시간" to "설정 → 🔕 모아 두기·요약 시간 한 곳에서 다 정해요. ① 수업 시간·퇴근 후에는 모으기로 한 앱 알림을 도착하자마자 숨기고 모아 두고 ② 정한 시각(여러 개)·1시간 단위 간격·수업이 끝날 때마다·출근할 때 중 고른 때 요약해요. 새 알림이 없으면 알리지 않아요. 예전 아침·저녁 시각은 그대로 옮겨 왔어요.",
    "📅 쉬는 날에 공휴일도" to "토·일·공휴일(대체공휴일 포함)에 할 일을 고르세요: 평일처럼 / 하루 종일 모아 두기 / 정한 시각 요약만 / 모두 쉬기. 재량휴업일은 ＋로 넣고, 학교 가는 공휴일은 눌러서 빼요.",
    "🔕 방해 금지 자동 켜기" to "권한을 한 번 허용하면 모아 두는 시간에 '쌤톡스 모아 두기' 모드를 켜서 메시지·알림 소리와 진동을 꺼요. 전화·알람은 그대로 울려요. 선생님이 직접 켠 방해 금지는 건드리지 않아요.",
    "📱 앱 아이콘이 보여요" to "설정 → 🌱 알림 앱 정리의 목록과 ＋ 앱 추가 창에 앱 아이콘이 같이 나와요. 이름이 같은 앱(캘린더 두 개 등)은 아래에 'Google 제공'·'삼성 제공'처럼 만든 곳을 적어 구분해요. 앱 추가 버튼도 크게 위로 올렸어요.",
    "👪 학부모 명부가 짧아졌어요" to "한 사람씩 줄로 세우던 명부를 이름 칩으로 옆으로 이어 붙였어요. 이름을 누르면 작은 창에서 연결한 이름·번호를 보고 빼거나 지울 수 있어요.",
    "🏠 홈 화면 위젯" to "홈 화면을 길게 누르고 → 위젯 → 쌤톡스 요약을 놓으세요. 가장 최근 요약이 위젯 안에 그대로 보이고(길면 위젯 안에서 올려 보기), 📋 요약을 누르면 지금까지 온 알림을 바로 정리해 위젯이 저절로 바뀌어요. 버튼 옆 숫자는 아직 요약 안 한 알림 수예요. ⚙️를 누르면 앱 설정이 열려요.",
    "🤖 봇·채널 알림은 사람 메시지에서 빼요" to "텔레그램 봇(자비스 등)·카카오톡 채널 알림톡(배송·결제)·카카오톡 자체 안내가 '사람에게 온 메시지'에 섞이던 것을 고쳤어요. 이런 알림은 📦 앱 알림에 '보낸 이: 내용'으로 보여요. 쌤톡스 자기 알림(요약 중·완료)도 더는 모으지 않아요.",
    "👥 사람 묶음을 내 맘대로" to "학생·학부모 묶음이 고정으로 있지 않아요. 설정 → 👥 사람 묶음에서 교직원·가족·학부모·동학년처럼 필요한 목록만 만들고, 이모지 24가지와 이름으로 꾸미세요. 어떤 묶음이든 지울 수 있어요(넣어 둔 이름도 같이 지워지고 모은 알림은 남아요). 예전 학생·학부모 묶음은 비어 있으면 저절로 없어지고, 이름이 들어 있으면 남아 있어요.",
    "🙋 답장할 연락을 맨 위에" to "학부모·동료가 묻거나 부탁한 것은 🙋 답장할 연락에 따로 모아요. 누가 무엇을 원하는지 한 줄로 보여 줘요.",
    "💬 대화방마다 한 줄씩" to "예전에는 알림을 한꺼번에 줄였다가 한 번 더 줄여서 내용이 뭉개졌어요. 이제 대화방마다 따로 '요점·요청·답장'을 정리해요. 날짜·시간·장소는 메시지에 있는 그대로 써요.",
    "🤖 기본 모델은 E4B" to "램 8GB 이상인 요즘 폰은 더 꼼꼼한 Gemma 4 E4B를 추천해요. 쓰던 모델을 바꾸려면 설정 → 🤖 AI 모델·권한 → 바꾸기.",
    "📦 쇼핑·배송·앱 알림은 건수만" to "사람이 보낸 메시지가 아닌 앱 알림은 AI가 요약하지 않고 앱별 건수와 최근 제목만 보여 줘요. 그래서 요약도 더 빨라졌어요.",
    "🧭 탭이 네 개로" to "📋 요약은 가장 최근 요약과 지금 정리하기만, 🔔 알림은 아직 요약 안 한 알림과 오늘 받은 알림 전체, 📚 지난 요약은 요약 모음이에요. 👥 사람 묶음은 설정 안으로 옮겼어요. 안 만들어도 요약은 그대로 돼요.",
    "👥 우리 반 → 사람들" to "아래 탭이 👥 사람들로 바뀌었어요. 교직원·가족처럼 묶음을 직접 만들고 이름만 쭉 넣으면, 그 이름이 보낸 알림이나 이름이 나온 알림을 묶음별로 모아 오늘 탭과 브리핑에 따로 보여 줘요. 학생·학부모 묶음도 이름을 바꾸거나 숨길 수 있어서 누구나 쓸 수 있어요.",
    "💼 업무·일정 앱도 한곳에" to "Gmail·Google Tasks·캘린더·메모 같은 업무·일정 앱은 메시지 앱 바로 아래 💼 묶음에 모이고, 브리핑 맨 위 \"💼 업무·일정 알림\"에 시간순으로 따로 보여요. 그 밖의 앱도 '⭐ 중요 앱으로 올리기'를 누르면 같이 관리돼요.",
    "🔤 글자가 커졌어요" to "앱 전체 글자를 한 단계씩 키우고 줄 간격을 넓혔어요. 오늘·사람들·설정 세 화면의 머리말과 카드 제목도 같은 모양으로 맞췄어요. 폰 설정에서 글자 크기를 키우면 쌤톡스도 같이 커져요.",
    "👪 학부모 명부는 이름만 쭉" to "관계를 따로 고를 필요 없이 이름을 쉼표·줄바꿈 아무렇게나 넣으면 돼요. '민준맘', '이서윤 아버님'처럼 쓰면 관계도 알아서 알아보고, 우리 반 학생 이름이 있으면 그 학생 학부모로 묶어요. 최근 연락한 사람은 누르기만 하면 바로 들어가요.",
    "＋ 앱 추가 목록이 깔끔해졌어요" to "내 파일·보안 폴더 같은 폰 기본 항목은 빼고 설치한 앱만 보여요. ⭐ 메시지·SNS 앱이 맨 위, 그다음 🔔 알림을 보내는 앱, 그 밖의 앱은 접어 둬요.",
    "🙅 안 쓰는 앱은 조용히" to "깔려만 있고 안 쓰는 앱(밴드 등)은 더 이상 \"알림이 안 들어와요 · 설정 열기\"로 보여 주지 않아요. 카톡·기본 문자 앱·직접 추가한 앱만 확인하고, '안 써요'를 누르면 다시 묻지 않아요.",
    "🧹 오래된 기록은 저절로 지워요" to "설정 → 🧹 저장 공간에서 받은 알림(3일~30일)·브리핑 모음(7일~90일)·학생 기록(3개월~1년) 보관 기간을 고를 수 있어요. 기간이 지나면 앱을 열지 않아도 저절로 지워지고, 일주일에 한 번 파일 크기까지 줄여서 앱이 무거워지지 않아요.",
    "＋ 알림 앱 추가" to "설정 → 🌱 알림 앱 정리에서 '＋ 앱 추가'를 누르면 폰에 깔린 앱 중 목록에 없는 앱을 골라 넣을 수 있어요. 한 번 뺀 앱도 이제 목록에서 사라지지 않아 언제든 다시 켤 수 있어요.",
    "💬 방 고르기가 편해졌어요" to "한 번이라도 알림이 온 방은 7일이 지나도 모두 목록에 남아요. 아직 알림이 안 온 방은 방 이름을 직접 넣어 미리 켜고 끌 수 있어요. (카톡 방 목록은 다른 앱이 읽을 수 없어서 이름으로 넣어요)",
    "👪 학부모 방 고르기 대신 학부모 명부" to "우리 반 → 👪 학부모 명부에서 학생·관계(어머님/아버님)·이름이나 번호만 등록하세요. 문자·카톡·밴드·하이클래스 등 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 보이면 자동으로 \"민준 어머님\" 연락으로 모아 답장까지 추천해요.",
    "🙋 한 사람으로 모아요" to "같은 학부모가 문자와 카톡으로 따로 연락해도 오늘 탭 👪 학부모 연락에 한 사람으로 묶여요. 이름·번호·카톡 별명을 여러 개 등록해도 돼요.",
    "🔔 정리는 백그라운드에서" to "지금 정리하기를 누르면 \"완료되면 알림으로 알려드릴게요\" 안내 뒤 앱이 닫히고, 백그라운드에서 정리해요. 다 되면 알림이 오고, 누르면 📄 세부 내역(요약 전체 + 원래 알림)이 바로 열려요.",
    "🧒 명부도 자동으로" to "학부모를 등록할 때 명부에 없는 학생이면 우리 반 명부에 같이 추가돼요.",
    "📋 지난 변화 (v1.0.2)" to "정리(요약)한 알림은 카드에서 사라지고, 오늘 받은 알림 전체는 맨 아래 '🗂 오늘 받은 알림 전체'에서 봐요.",
    "🧭 지난 큰 변화 (v1.0)" to "아래 탭이 📋 오늘 · 🧒 우리 반 · ⚙️ 설정 세 개로 바뀌었고, 설정은 목록에서 세부 화면으로 들어가요.",
)

/**
 * 메인: 아래 탭 네 개 — 손대지 않아도 알림을 주기적으로 요약해 보는 앱
 *  📋 요약 — 가장 최근 요약·할 일·연락, 지금 정리하기
 *  🔔 알림 — 아직 요약 안 한 알림, 오늘 받은 알림 전체(앱·방별)
 *  📚 지난 요약 — 요약 모음
 *  ⚙️ 설정 — 앱 동작(알림 앱·시간·AI 모델·백업)과 선택 사항인 👥 사람 묶음, 목록 → 세부 화면
 */
@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    // 폰의 뒤로 버튼: 세부 화면 → 탭 첫 화면 → 요약 탭
    androidx.activity.compose.BackHandler(enabled = vm.page != null || vm.tab != JarvisViewModel.Tab.BRIEFING) { vm.back() }
    vm.roomAction?.let { (pkg, room) -> RoomActionDialog(vm, pkg, room) }
    Scaffold(
        bottomBar = {
            // 하단 메뉴: 흰 바탕 + 위 옅은 줄, 선 아이콘, 고른 칸은 세이지 알약·굵은 글자
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                listOf(
                    Triple(JarvisViewModel.Tab.BRIEFING, R.drawable.ic_line_summary, "요약"),
                    Triple(JarvisViewModel.Tab.ALERTS, R.drawable.ic_line_bell, "알림"),
                    Triple(JarvisViewModel.Tab.HISTORY, R.drawable.ic_line_history, "지난 요약"),
                    Triple(JarvisViewModel.Tab.SETTINGS, R.drawable.ic_line_settings, "설정"),
                ).forEach { (t, icon, label) ->
                    val sel = vm.tab == t
                    NavigationBarItem(
                        selected = sel,
                        onClick = { if (!sel || vm.page != null) vm.selectTab(t) },   // 같은 탭을 또 누르면 아무것도 안 함
                        // 이모지 대신 선 아이콘(2026-11-25 디자인)
                        icon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null,
                            modifier = Modifier.size(24.dp)) },
                        label = { Text(label, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        // 화면 위치: (탭, 세부 화면). 세부 화면으로 들어가면 옆에서 밀려 들어오고(iOS처럼), 탭끼리는 살짝 겹쳐 바뀜
        val route = vm.tab to vm.page
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val (fromTab, fromPage) = initialState
                val (toTab, toPage) = targetState
                when {
                    fromTab == toTab && fromPage == null && toPage != null ->      // 들어가기
                        (slideInHorizontally(Motion.offset) { it } + fadeIn(Motion.fade)) togetherWith
                            (slideOutHorizontally(Motion.offset) { -it / 4 } + fadeOut(Motion.fadeFast))
                    fromTab == toTab && fromPage != null && toPage == null ->      // 뒤로
                        (slideInHorizontally(Motion.offset) { -it / 4 } + fadeIn(Motion.fade)) togetherWith
                            (slideOutHorizontally(Motion.offset) { it } + fadeOut(Motion.fadeFast))
                    else ->                                                        // 탭 바꾸기
                        fadeIn(tween(140)) togetherWith fadeOut(tween(90))
                }
            },
            modifier = Modifier.padding(inner),
            label = "route",
        ) { (tab, page) ->
            val scroll = rememberScrollState()
            // 🏠 위젯으로 열면 맨 위(최신 요약)부터 — 이미 요약 탭을 내려 보던 중이어도
            LaunchedEffect(vm.scrollTopSignal) { if (vm.scrollTopSignal > 0) scroll.scrollTo(0) }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                if (page == null) Header(vm, tab) else PageHeader(vm, page)
                if (page != null) PageContent(vm, page)
                else when (tab) {
                    JarvisViewModel.Tab.BRIEFING -> BriefingTab(vm)
                    JarvisViewModel.Tab.ALERTS -> AlertsTab(vm)
                    JarvisViewModel.Tab.HISTORY -> HistoryTab(vm)
                    JarvisViewModel.Tab.SETTINGS -> SettingsTab(vm)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Header(vm: JarvisViewModel, tab: JarvisViewModel.Tab) {
    // 네 탭 모두 같은 모양: 작은 로고 + 큰 제목, 아래 한 줄 설명
    val today = remember { SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(System.currentTimeMillis()) }
    val (title, sub) = when (tab) {
        JarvisViewModel.Tab.BRIEFING -> "요약" to today
        JarvisViewModel.Tab.ALERTS -> "알림" to "오늘 ${vm.todayItems.size}건 · 아직 요약 안 한 알림 ${vm.pendingItems.size}건"
        JarvisViewModel.Tab.HISTORY -> "지난 요약" to "최근 요약 ${vm.briefings.size}개"
        JarvisViewModel.Tab.SETTINGS -> "설정" to "쌤톡스 ${vm.versionLine}"
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SsamLogo(36.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

/** 세부 화면 위: ‹ 뒤로 + 제목 */
@Composable
private fun PageHeader(vm: JarvisViewModel, page: JarvisViewModel.Page) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(start = 0.dp, end = 8.dp)) {
            Text("‹ " + (page.parent?.title?.let { menuIcon(it).second } ?: when (page.tab) {
                JarvisViewModel.Tab.BRIEFING -> "요약"; JarvisViewModel.Tab.ALERTS -> "알림"
                JarvisViewModel.Tab.HISTORY -> "지난 요약"; else -> "설정" }),
                style = MaterialTheme.typography.titleMedium)
        }
    }
    MenuTitle(if (page == JarvisViewModel.Page.GROUP) vm.currentGroup?.title ?: page.title else page.title,
        style = MaterialTheme.typography.headlineSmall, iconSize = 26.dp, tint = MaterialTheme.colorScheme.primary)
}

/** 세부 화면 내용: 예전 설정 칸들을 그대로 씀 */
@Composable
private fun PageContent(vm: JarvisViewModel, page: JarvisViewModel.Page) {
    vm.roomApp?.let { (pkg, name) -> RoomPickerDialog(vm, pkg, name) }
    when (page) {
        JarvisViewModel.Page.APPS -> AppCleanupCard(vm)
        JarvisViewModel.Page.SCHEDULE -> SchedulePage(vm)
        JarvisViewModel.Page.SYSTEM -> SystemSettings(vm)
        JarvisViewModel.Page.BACKUP -> BackupCard(vm)
        JarvisViewModel.Page.STORAGE -> StoragePage(vm)
        JarvisViewModel.Page.PEOPLE -> PeoplePage(vm)
        JarvisViewModel.Page.GROUP -> GroupPage(vm)
        JarvisViewModel.Page.KEYWORDS -> KeywordCard(vm)
        JarvisViewModel.Page.MY_NAMES -> MyNamesCard(vm)
        JarvisViewModel.Page.PINNED -> PinnedRoomsPage(vm)
    }
}

/** 아이폰 설정 앱 같은 한 줄 메뉴: 아이콘·제목·지금 상태·› */
@Composable
private fun MenuGroup(rows: List<Triple<String, String, () -> Unit>>) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, (title, status, onClick) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MenuTitle(title, Modifier.weight(1f))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ───────────────────────── 브리핑 탭: 결과만 ─────────────────────────

@Composable
private fun BriefingTab(vm: JarvisViewModel) {

    // 🆕 업데이트 내역: 업데이트 뒤 처음 한 번만, 8초 뒤 저절로 사라짐. 누르면 큰 창으로
    var whatsNewOpen by rememberSaveable { mutableStateOf(false) }
    if (vm.showWhatsNew) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); if (!whatsNewOpen) vm.closeWhatsNew() }
    }
    AnimatedVisibility(visible = vm.showWhatsNew, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
        exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
        Card(Modifier.fillMaxWidth().clickable { whatsNewOpen = true }) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🆕 ${vm.versionLine} · $WHATS_NEW", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text("자세히 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    // 🏠 위젯을 아직 안 놓았으면 한 번 권함(닫으면 다시 안 보임 — 설정에서 언제든)
    if (!vm.hasWidget && !vm.widgetTipClosed) Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🏠 홈 화면에 요약 위젯", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                Text("앱을 열지 않고 최근 요약을 바로 봐요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { vm.pinWidget() }) { Text("위젯 놓기") }
            TextButton(onClick = { vm.closeWidgetTip() }) { Text("닫기") }
        }
    }
    // 📦 잠깐 나갔던 com.ssamtox 판(v1.15.0)이 남아 있으면 지우게 — 두 앱이 함께 있으면 알림을 두 번 모음(2026-11-25 되돌림)
    if (vm.oldAppInstalled) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)) {
            Text("📦 쌤톡스가 두 개 설치돼 있어요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("잠깐 나갔던 새 이름 판(v1.15.0)이 함께 깔려 있어요. 이 앱이 원래 쌤톡스예요 — 다른 하나는 지워 주세요. " +
                "두 앱이 함께 있으면 알림을 두 번 모아요. (그쪽에서 바꾼 설정이 있으면 지우기 전에 백업 → 여기서 복원)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { vm.openOldAppSettings() }) { Text("다른 하나 지우기") }
                TextButton(onClick = { vm.open(JarvisViewModel.Page.BACKUP) }) { Text("복원하기") }
            }
        }
    }
    // 🙋 나를 부르는 말을 아직 안 넣었으면 한 번 권함 — 나에게 온 대화를 알아보는 기준(닫으면 다시 안 보임)
    if (vm.myNames.isEmpty() && !vm.myNamesTipClosed) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)) {
            Text("🙋 나를 부르는 말을 넣어 주세요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("이름·별명·단톡방 닉네임(예: 김민수, 민수쌤)을 넣으면, 나를 부르거나 멘션한 대화만 내용까지 요약하고 나머지 방은 짧게 줄여요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { vm.open(JarvisViewModel.Page.MY_NAMES) }) { Text("넣기") }
                TextButton(onClick = { vm.closeMyNamesTip() }) { Text("닫기") }
            }
        }
    }
    if (whatsNewOpen) AlertDialog(
        onDismissRequest = { whatsNewOpen = false; vm.closeWhatsNew() },
        title = { Column { Text("🆕 v${vm.appVersion} 바뀐 점"); Text(vm.versionLine.substringAfter(" · ", ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WHATS_NEW_DETAIL.forEach { (title, body) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { whatsNewOpen = false; vm.closeWhatsNew() }) { Text("확인") } },
    )

    // 가장 많이 쓰는 '지금 정리하기'는 맨 위에 (예전엔 할 일 목록 아래에 묻혀 안 보였음)
    val ctx = LocalContext.current
    Button(
        onClick = {
            vm.summarizeToday()
            // 🔔 안내 후 앱을 뒤로 보내기 (알림이 꺼져 있으면 결과를 못 받으니 화면에 남음)
            if (androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                android.widget.Toast.makeText(ctx, "정리를 시작했어요. 완료되면 알림으로 알려드릴게요", android.widget.Toast.LENGTH_LONG).show()
                (ctx as? android.app.Activity)?.moveTaskToBack(true)
            } else {
                android.widget.Toast.makeText(ctx, "알림이 꺼져 있어서 완료 알림을 못 보내요. 이 화면에서 기다려 주세요.", android.widget.Toast.LENGTH_LONG).show()
            }
        },
        enabled = vm.hasModel && !vm.busy && !vm.bgRunning && vm.pendingCount > 0,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = actionButtonColors(),  // 🟢 이 화면의 초록 단추는 이것 하나
    ) {
        Text(when {
            vm.bgRunning -> "백그라운드에서 정리 중… 다 되면 알림"
            vm.pendingCount > 0 -> "지금 정리하기 (새 알림 ${vm.pendingCount}건)"
            else -> "새 알림이 없어요 · 모두 정리됨"
        })
    }
    if (!vm.bgRunning && vm.pendingCount > 0) Text("🔔 누르면 백그라운드에서 정리하고 앱은 닫혀요. 다 되면 알림이 오고, 누르면 세부 내역을 볼 수 있어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.busy || vm.bgRunning) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.stopSummary() }, enabled = !vm.stopping, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.stopping) "멈추는 중…" else "중지 (지금까지 정리한 것만 보기)")
        }
    }
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    if (!vm.hasModel) Text("AI 모델이 없어요. 설정 탭에서 받아 주세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    vm.briefingDetail?.let { (b, items) -> BriefingDetailDialog(vm, b, items) }
    if (vm.replyDrafts.isNotEmpty()) ReplyDraftsCard(vm)
    // 최신 요약 카드(2026-11-25 선생님: '요약창에 요약된 내용이 안 보임'): 예전엔 가장 최근 것 하나만 보여 줘서, 그게 숨긴 하루 정리면
    // 방금 한 요약이 있어도 요약 탭이 텅 비었다. 이제 최근 요약과 오늘의 하루 정리를 따로 — 숨긴 것은 '다시 보기' 한 줄로
    val latest = vm.briefings.firstOrNull { !DailyDigest.isDaily(it) }
    val todayStart = remember(vm.briefings) { SummarySchedule.startOfDay(System.currentTimeMillis()) }
    val daily = vm.briefings.firstOrNull { DailyDigest.isDaily(it) && it.time >= todayStart }
    if (latest == null && daily == null) Card(Modifier.fillMaxWidth()) {
        Text("아직 요약이 없어요. 위 버튼을 누르거나, 설정에서 정한 시각을 기다리세요.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    val timeFmtCard = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    listOfNotNull(latest, daily).sortedByDescending { it.time }.forEach { b ->
        if (b.time.toString() !in vm.hiddenBriefings) BriefingCard(vm, b, primary = b === latest, onHide = { vm.hideBriefing(b) })
        else TextButton(onClick = { vm.unhideBriefing(b) }) {
            Text("${b.label} (${timeFmtCard.format(b.time)})은 숨겼어요 · 다시 보기", style = MaterialTheme.typography.labelLarge)
        }
    }

    // 📌 할 일·마감 카드는 없앰(2026-11-25 선생님: 할 일인지 아닌지 구분이 어려워 불필요)
    // 2026-11-24 선생님: 학부모 고정 칸 대신 내가 만든 👥 사람 묶음마다 그 이름으로 연락 정리
    PeopleCards(vm)

}

// ───────────────────────── 🔔 알림 탭: 모은 알림 원본 ─────────────────────────

@Composable
private fun AlertsTab(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    // 🔎 메시지 내용 검색(2026-11-22 선생님): 모아 둔 알림 전체에서. 찾는 동안에는 결과만 보임
    OutlinedTextField(value = vm.searchQuery, onValueChange = { vm.search(it) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), placeholder = { Text("🔎 메시지 검색 (내용·보낸 사람·방 이름)") },
        trailingIcon = { if (vm.searchQuery.isNotEmpty()) TextButton(onClick = { vm.search("") }) { Text("지우기") } })
    if (vm.searchQuery.isNotBlank()) { SearchResults(vm); return }
    // 아직 요약 안 한 알림: 요약 탭에서 '지금 정리하기'를 하면 사라짐
    if (vm.pendingItems.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val apps = vm.pendingItems.map { it.app }.distinct()
        // 아직 요약 안 한 알림은 연두 카드로 눈에 띄게
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("아직 요약 안 한 알림 ${vm.pendingItems.size}건", style = MaterialTheme.typography.titleMedium)
                        Text(apps.take(3).joinToString(", ") + if (apps.size > 3) " 외 ${apps.size - 3}개 앱" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "더 보기") }
                }
                // 접어 두면 최근 3건만, 펼치면 30건
                Column(Modifier.animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    run {
                        vm.pendingItems.takeLast(if (expanded) 30 else 3).reversed().forEach {
                            Text("${timeFmt.format(it.time)} [${it.app}] ${it.title}: ${it.text}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    Text("오늘 받은 알림 전체 · 앱·대화방별", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Text("요약에 필요 없는 알림(서버 알림·매일 오는 안내 등)은 길게 눌러 요약에서 뺄 수 있어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TodayAllPage(vm)
    MutedShapesCard(vm)
}

/** 🔕 요약에서 뺀 알림 꼴: 되돌리기. 하나도 없으면 안 보임 */
@Composable
private fun MutedShapesCard(vm: JarvisViewModel) {
    if (vm.mutedShapes.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🔕 요약에서 뺀 알림 ${vm.mutedShapes.size}가지", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("이런 알림은 모으지만 요약·하루 정리에는 넣지 않아요. 매일 같은 시각에 오는 알림은 쌤톡스가 알아서 빼고 요약 끝에 건수만 적어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.mutedShapes.sorted().forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.mutedLabel(k), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    TextButton(onClick = { vm.unmute(k) }) { Text("되돌리기") }
                }
            }
        }
    }
}

/** 🔎 검색 결과: 최근 것부터 100건까지, 찾은 낱말은 굵게 */
@Composable
private fun SearchResults(vm: JarvisViewModel) {
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    val list = vm.searchResults ?: run { Text("찾는 중…", style = MaterialTheme.typography.bodySmall); return }
    val words = vm.searchQuery.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    Text(if (list.isEmpty()) "'${vm.searchQuery.trim()}' 이(가) 든 알림이 없어요. 원래 알림은 ${Storage.label(vm.notiDays)} 지나면 지워져요."
         else "${list.size}건" + (if (list.size >= 100) " (최근 100건까지)" else "") + " · 최근 것부터",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEach { n ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val who = if (n.room.isNotEmpty() && n.title != n.room) "${n.room} · ${n.title}" else n.title
                Text("$who · ${n.app} · ${fmt.format(n.time)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(highlight(n.text.ifBlank { n.title }, words), style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            }
        }
    }
}

/** 글 속 낱말을 굵게(대소문자 구분 없이) */
private fun highlight(text: String, words: List<String>) = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    words.forEach { w ->
        var i = text.indexOf(w, ignoreCase = true)
        while (w.isNotEmpty() && i >= 0) {
            addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, background = androidx.compose.ui.graphics.Color(0x55FFE066)), i, i + w.length)
            i = text.indexOf(w, i + w.length, ignoreCase = true)
        }
    }
}

// ───────────────────────── 📚 지난 요약 탭 ─────────────────────────

@Composable
private fun HistoryTab(vm: JarvisViewModel) {
    vm.briefingDetail?.let { (b, items) -> BriefingDetailDialog(vm, b, items) }
    if (vm.briefings.isEmpty()) Card(Modifier.fillMaxWidth()) {
        Text("아직 요약이 없어요. 설정에서 정한 시각이 되면 저절로 쌓여요.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    // 지난 요약을 날짜별로 묶어 한 줄씩(2026-11-22 선생님: 요약이 길어지면 날짜별로). 오늘만 펼쳐 두고, 줄을 누르면 내용
    if (vm.briefings.isNotEmpty()) {
        val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
        val days = remember(vm.briefings) { vm.briefings.groupBy { SummarySchedule.startOfDay(it.time) }.toList() }
        val today = SummarySchedule.startOfDay(System.currentTimeMillis())
        Text("📚 요약 모음 · ${vm.briefings.size}개 · 요약은 ${Storage.label(vm.briefDays)}, 원래 알림은 ${Storage.label(vm.notiDays)} 지나면 저절로 지워져요 (설정 → 🧹 저장 공간)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        days.forEach { (day, list) ->
            var open by rememberSaveable(day) { mutableStateOf(day == today) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                        Text(dayTitle(day, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading().weight(1f))
                        Text("요약 ${list.size}개 · 알림 ${list.sumOf { it.count }}건", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (open) "  ▾" else "  ▸")
                    }
                    AnimatedVisibility(visible = open, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                        exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { b ->
                                var show by rememberSaveable(b.time) { mutableStateOf(false) }
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().clickable { show = !show }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(historyLine(b, timeFmt.format(b.time)),
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(if (show) "▾" else "▸")
                                }
                                AnimatedVisibility(visible = show, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                                    exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SummaryText(b.text, onRoom = vm::askRoom)
                                        TextButton(onClick = { vm.openBriefingDetail(b) }) { Text("📄 원래 알림 보기") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ✉️ 답장할 문자 (2026-11-22): 문자로 온 🙋 답장할 연락마다 초안 → 고쳐 쓰고 보내기. 자동으로 보내지 않음.
 * 카톡은 카톡 안에 답장 추천이 있어 하지 않음(선생님 결정).
 */
@Composable
private fun ReplyDraftsCard(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    var open by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("✉️ 답장할 문자 · ${vm.replyDrafts.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading().weight(1f))
                Text(if (open) "▾" else "▸")
            }
            if (open) {
                Text("AI가 쓴 초안이에요. 고친 뒤 보내기를 눌러야만 나가요. 모르는 내용은 [ ]로 비워 두었어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.replyDrafts.forEach { d ->
                    HorizontalDivider()
                    var text by rememberSaveable(d.sender, d.lastTime, d.draft) { mutableStateOf(d.draft) }
                    Text("${d.sender} · ${timeFmt.format(d.lastTime)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                    Text("“${d.question}”", style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    if (vm.draftingFor == d.sender) {
                        Text("✏️ 초안 쓰는 중… (처음엔 모델을 불러오느라 30초쯤)", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (d.draft.isEmpty() && text.isEmpty()) {
                        OutlinedButton(onClick = { vm.makeDraft(d) }, enabled = vm.hasModel && vm.draftingFor == null && !vm.bgRunning) { Text("✏️ 초안 만들기") }
                    }
                    if (d.draft.isNotEmpty() || text.isNotEmpty()) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                // [ ] 빈칸(AI가 모르는 사실)을 채우지 않고 나가지 않게
                                if (Regex("\\[[^\\]]*\\]").containsMatchIn(text)) {
                                    android.widget.Toast.makeText(ctx, "[ ] 빈칸을 먼저 채워 주세요", android.widget.Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                val msg = vm.sendReply(d, text)
                                android.widget.Toast.makeText(ctx, Emoji.strip(msg), android.widget.Toast.LENGTH_LONG).show()
                            }, enabled = text.isNotBlank()) { Text(if (vm.canReplyDirectly(d)) "💬 보내기" else "📋 복사·문자 앱") }
                            OutlinedButton(onClick = { vm.makeDraft(d) }, enabled = vm.hasModel && vm.draftingFor == null && !vm.bgRunning) { Text("다시 쓰기") }
                            TextButton(onClick = { vm.doneReply(d) }) { Text("✓ 끝") }
                        }
                    } else {
                        TextButton(onClick = { vm.doneReply(d) }) { Text("✓ 답장 안 해도 돼요") }
                    }
                }
            }
        }
    }
}

/** 요약 모음 한 줄: 이름에 시각이 있으면 그대로(📋 12:00 요약), 미리 준비해 시각이 다르면 정리한 시각을, 없으면(아침·저녁) 시각을 붙임 */
private fun historyLine(b: Briefing, hm: String): String {
    val named = Regex("\\d{2}:\\d{2}").find(b.label)?.value
    val head = if (b.label.firstOrNull()?.isLetterOrDigit() == false) b.label else "${briefingIcon(b)} ${b.label}"   // 🌅·📋·⏱ 처럼 이미 아이콘이 있으면 그대로
    return when (named) {
        null -> "$head · $hm"
        hm -> head
        else -> "$head · $hm 정리"
    } + if (DailyDigest.isDaily(b)) "" else " · ${b.count}건"
}

/** 요약 모음 날짜 머리: 오늘 · 11/22 (일), 어제 · 11/21 (토), 11/20 (금) */
private fun dayTitle(day: Long, today: Long): String {
    val d = SimpleDateFormat("M/d (E)", Locale.KOREA).format(day)
    return when (day) {
        today -> "오늘 · $d"
        SummarySchedule.addDays(today, -1) -> "어제 · $d"
        else -> d
    }
}

/** 🗂 오늘 받은 알림 전체: 앱·방별로 묶어 최근 것부터 (정리한 알림 포함) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayAllPage(vm: JarvisViewModel) {
    var muteAsk by remember { mutableStateOf<NotiItem?>(null) }
    muteAsk?.let { n ->
        AlertDialog(onDismissRequest = { muteAsk = null },
            title = { Text("이런 알림 요약에서 빼기") },
            text = { Text("${n.app} · '${Routine.shape(n.title).ifBlank { "제목 없음" }}' 꼴의 알림을 앞으로 요약·하루 정리에 넣지 않아요(숫자만 다른 것도 같이). 알림 탭·검색에는 그대로 남고, 언제든 되돌릴 수 있어요.") },
            confirmButton = { TextButton(onClick = { vm.muteLike(n); muteAsk = null }) { Text("빼기") } },
            dismissButton = { TextButton(onClick = { muteAsk = null }) { Text("취소") } })
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    if (vm.todayItems.isEmpty()) {
        Text("오늘 모은 알림이 아직 없어요.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("오늘 ${vm.todayItems.size}건 · 정리 안 한 알림 ${vm.pendingItems.size}건", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rooms = remember(vm.todayItems) {
        vm.todayItems.groupBy { it.app to it.roomOrTitle }.entries
            .map { it.key to it.value }.sortedByDescending { (_, l) -> l.maxOf { it.time } }
    }
    rooms.forEach { (key, list) ->
            var open by rememberSaveable(key.first + key.second) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.animateContentSize(Motion.size).clickable { open = !open }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.second, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.asHeading())
                            Text("${key.first} · ${list.size}건 · 마지막 ${timeFmt.format(list.maxOf { it.time })}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (open) "▾" else "▸")
                    }
                    val shown = if (open) list.takeLast(50).reversed() else list.takeLast(1)
                    shown.forEach {
                        val who = if (it.room.isNotEmpty() && it.title != it.room) "${it.title}: " else ""
                        val n = it
                        Text("${timeFmt.format(it.time)}  $who${it.text}", style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (open) 6 else 1,
                            modifier = Modifier.combinedClickable(onClick = { open = !open }, onLongClick = { muteAsk = n }))
                    }
                }
            }
        }
}

private fun briefingIcon(b: Briefing) = when {
    "아침" in b.label -> "🌅"
    "저녁" in b.label -> "🌙"
    else -> "📋"
}

/** 📄 브리핑 세부 내역: 요약 전체 + 이번에 정리한 원래 알림(앱·방별). 완료 알림을 누르면 바로 열림. */
@Composable
private fun BriefingDetailDialog(vm: JarvisViewModel, b: Briefing, items: List<NotiItem>) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.closeBriefingDetail() },
        title = {
            Column {
                Text("${briefingIcon(b)} ${b.label}", style = MaterialTheme.typography.titleMedium)
                // 🗓 하루 정리는 건수를 저장하지 않음(지난 요약 합계에 두 번 세지 않게) — 세부 창에도 '알림 0건'이 뜨던 것(2026-11-24)
                Text("${fmt.format(b.fromTime)} ~ ${timeFmt.format(b.time)}" + if (DailyDigest.isDaily(b)) "" else " · 알림 ${b.count}건",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryText(b.text, onRoom = vm::askRoom)
                HorizontalDivider()
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "▾ 원래 알림 접기" else "▸ 원래 알림 ${items.size}건 보기")
                }
                AnimatedVisibility(visible = showRaw, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                    exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.groupBy { it.app to it.roomOrTitle }.forEach { (k, list) ->
                            Column {
                                Text("${k.first} · ${k.second} (${list.size})",
                                    style = MaterialTheme.typography.labelLarge)
                                list.sortedBy { it.time }.forEach { n ->
                                    val who = if (n.room.isNotEmpty() && n.title != n.room) "${n.title}: " else ""
                                    Text("${timeFmt.format(n.time)} $who${n.text}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (items.isEmpty()) Text("원래 알림은 보관 기간이 지나 지워졌어요. (설정 → 🧹 저장 공간)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.closeBriefingDetail() }) { Text("닫기") } },
    )
}

/**
 * 브리핑 카드: 제목 줄(언제·몇 건)만 보이게 접을 수 있고, 숨기면 맨 위에서 빠짐.
 * 한 번 접으면 다시 열어도 접힌 채로 (보고 나면 접어 두기).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefingCard(vm: JarvisViewModel, b: Briefing, primary: Boolean, onHide: () -> Unit) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val collapsed = b.time.toString() in vm.collapsedBriefings
    Card(Modifier.fillMaxWidth(), colors = if (primary) CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(horizontal = Space.card, vertical = Space.l)) {
            Row(Modifier.fillMaxWidth().clickable { vm.toggleBriefing(b) }.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    // 🗓 하루 정리는 건수를 저장하지 않음(지난 요약 합계에 두 번 세지 않게) — '알림 0건'이 뜨던 것(2026-11-23)
                    Text(fmt.format(b.time) + if (DailyDigest.isDaily(b)) "" else " · 알림 ${b.count}건", style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f))
                }
                Text(if (collapsed) "펼치기 ▾" else "접기 ▴", style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.8f))
            }
            // 🎬 접기·펼치기: 아래로 스르륵
            AnimatedVisibility(visible = !collapsed, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = Space.l), color = LocalContentColor.current.copy(alpha = 0.15f))
                    // 🧾 줄의 꼴을 보고 칸 제목·사람·시각·내용을 나눠 그림(SummaryText)
                    SummaryText(b.text, accent = if (primary) Ssam.GoldPale else MaterialTheme.colorScheme.primary, onRoom = vm::askRoom)
                    Spacer(Modifier.height(Space.s))
                    // 좁은 화면·큰 글자에서 '숨기기'가 한 글자씩 세로로 깨지던 것(2026-11-23): 모자라면 다음 줄로, 단추 글은 한 줄로
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.openBriefingDetail(b) }) {
                            Text("📄 세부 내역", color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, softWrap = false)
                        }
                        TextButton(onClick = { vm.toggleBriefing(b) }) {
                            Text("다 봤어요 · 접기", color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, softWrap = false)
                        }
                        TextButton(onClick = onHide) {
                            Text("숨기기", color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 설정 탭: 내 방식대로 ─────────────────────────

@Composable
private fun SettingsTab(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val rev = vm.quietRev   // 수업·퇴근 설정이 바뀌면 상태 줄도 다시 그림
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    val fmt = remember { SimpleDateFormat("M/d", Locale.KOREA) }
    val sc = vm.schedule
    val scheduleStatus = if (!sc.digest) "꺼짐 · 직접 눌러 요약" else listOfNotNull(
        sc.times.takeIf { it.isNotEmpty() }?.joinToString("·") { hm(it) },
        if (sc.intervalHours > 0) "${sc.intervalHours}시간마다" else null,
    ).joinToString(" · ").ifEmpty { "켜짐 · 알릴 시간을 정해 주세요" }
    val quietStatus = listOfNotNull(
        if (QuietMode.classOn(ctx)) "수업 중 켜짐" else null,
        if (QuietMode.afterWorkOn(ctx)) "퇴근 ${hm(QuietMode.workEnd(ctx))}" else null,
    ).joinToString(" · ").ifEmpty { if (rev >= 0) "꺼짐" else "" }
    val permsMissing = listOf(vm.listenerEnabled, vm.notiPermission).count { !it }

    // 2026-11-26 선생님: 컨셉을 명확하게, 설정은 적게 — 쌤톡스가 보여 주는 것은 넷뿐:
    // 나를 부르거나 멘션한 말(단톡방은 이것만) · 나에게 온 1:1 · 꼭 받을 사람 · 키워드. 그 밖의 방은 건수만
    Text("쌤톡스가 보여 주는 것", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MenuGroup(listOf(
        Triple("🙋 나를 부르는 말",
            if (vm.myNames.isEmpty()) "아직 없음 — 넣어야 단톡방에서 나를 부른 말을 찾아요" else vm.myNames.take(3).joinToString(", ") + if (vm.myNames.size > 3) " 외 ${vm.myNames.size - 3}개" else "") {
            vm.open(JarvisViewModel.Page.MY_NAMES) },
        Triple("👥 꼭 받을 사람", "묶음 ${vm.groups.count { !it.hidden }}개 · 이 사람들 연락은 늘 요약") { vm.open(JarvisViewModel.Page.PEOPLE) },
        Triple("⭐ 키워드",
            if (vm.keywords.isEmpty()) "아직 없음 · 이 말이 나오면 놓치지 않게" else vm.keywords.take(2).joinToString(", ") + if (vm.keywords.size > 2) " 외 ${vm.keywords.size - 2}개" else "") {
            vm.open(JarvisViewModel.Page.KEYWORDS) },
        Triple("📌 놓치기 싫은 방", if (vm.pinnedRooms.isEmpty()) "나를 안 불러도 꼭 볼 단톡방" else "${vm.pinnedRooms.size}곳 · 늘 내용까지 요약") { vm.open(JarvisViewModel.Page.PINNED) },
    ))
    Text("나에게 온 1:1(문자·답한 적 있는 사람)은 따로 정하지 않아도 보여 줘요. 그 밖의 단톡방은 건수만 적어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MenuGroup(listOf(
        Triple("🔕 모아서 알림", scheduleStatus) { vm.open(JarvisViewModel.Page.SCHEDULE) },
        Triple("🌱 알림 앱 정리", "뺀 앱 ${vm.excludedApps.size}개 · 광고 ${vm.adCount}건") { vm.open(JarvisViewModel.Page.APPS) },
        Triple("🏠 홈 화면 위젯", if (vm.hasWidget) "놓여 있어요 · 하나 더 놓기" else "위젯 놓기 — 요약을 홈 화면에서 바로") { vm.pinWidget() },
    ))
    // 📖 설치 방법(2026-11-25 선생님: Play 프로텍트 '앱 차단됨' 등 — 동료에게 보낼 안내 페이지). 앱은 인터넷 권한이 없어 브라우저로 엶
    MenuGroup(listOf(
        Triple("📖 설치 방법 보기", "Play 프로텍트 차단·알림 접근 잠김 풀기") { vm.openInstallGuide() },
        Triple("📤 동료에게 설치 링크 보내기", "카톡·문자로 설치 방법 페이지 보내기") { vm.shareInstallGuide() },
    ))
    MenuGroup(listOf(
        Triple("🤖 AI 모델·권한",
            (vm.installedModel?.title ?: if (vm.hasModel) "모델 있음" else "모델 없음") + if (permsMissing > 0) " · 권한 확인 $permsMissing" else "") {
            vm.open(JarvisViewModel.Page.SYSTEM) },
        Triple("💾 백업·복원",
            // 비밀번호가 없으면 자동 백업이 안 됨(암호화해서 저장) — 메뉴에서 바로 보이게(2026-11-25)
            if (!vm.hasBackupPin) "비밀번호를 정하면 매일 자동 백업" else (if (vm.lastBackup > 0) "${fmt.format(vm.lastBackup)} 백업" else "백업 없음") + " · 매일 자동 · 🔒") {
            vm.open(JarvisViewModel.Page.BACKUP) },
        Triple("🧹 저장 공간",
            (vm.storageUsage?.let { Storage.size(it.bytes) + " · " } ?: "") + "알림 ${Storage.label(vm.notiDays)} 보관 · 자동 정리") {
            vm.open(JarvisViewModel.Page.STORAGE) },
    ))
}

/**
 * 🔧 틀린 것 바로잡기(2026-11-26): 요약에서 방·사람 이름을 누르면 뜨는 창.
 * 놓친 방은 📌 놓치기 싫은 방으로 올리고, 나와 상관없는데 자꾸 올라오는 방은 건수만 보게 내림 — 설정에 들어가지 않고 그 자리에서
 */
@Composable
private fun RoomActionDialog(vm: JarvisViewModel, pkg: String, room: String) {
    val tier = vm.roomTier(pkg, room)
    AlertDialog(
        onDismissRequest = { vm.closeRoomAction() },
        title = { Text(room, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (tier) {
                        Relevance.ME -> "지금: 📌 놓치기 싫은 방 — 나를 부르지 않아도 늘 내용까지 요약해요."
                        Relevance.OTHER -> "지금: 건수만 — 나를 불러도 요약하지 않아요."
                        else -> "지금: 저절로 — 나를 부르거나 멘션한 말만 요약하고, 나머지는 건수만 적어요."
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tier != Relevance.ME) Button(onClick = { vm.pinRoom(pkg, room); vm.closeRoomAction() }, modifier = Modifier.fillMaxWidth()) {
                    Text("📌 놓치기 싫은 방으로")
                }
                if (tier != Relevance.OTHER) OutlinedButton(onClick = { vm.muteRoom(pkg, room, true); vm.closeRoomAction() }, modifier = Modifier.fillMaxWidth()) {
                    Text("이 방은 건수만 보기")
                }
                if (tier != Relevance.AUTO) OutlinedButton(onClick = { vm.muteRoom(pkg, room, false); vm.closeRoomAction() }, modifier = Modifier.fillMaxWidth()) {
                    Text("저절로 돌리기")
                }
                Text("다음 요약부터 적용돼요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeRoomAction() }) { Text("닫기") } },
    )
}

// ───────────────────────── 📌 놓치기 싫은 방 (설정 → 세부 화면) ─────────────────────────

/**
 * 📌 놓치기 싫은 방(2026-11-26 선생님): 정기 요약·하루 정리는 나와 관련된 것만 보여 주므로, 나를 부르지 않아도 꼭 볼 방
 * (읽기만 하는 동학년방 공지 등)을 한곳에서 고름. 방 고르기의 '늘 중요'와 같은 값(RoomTier)
 */
@Composable
private fun PinnedRoomsPage(vm: JarvisViewModel) {
    val pinned = vm.pinnedRooms
    val pinnedKeys = pinned.map { "${it.first}|${it.second}" }.toSet()
    var q by rememberSaveable { mutableStateOf("") }
    Text("여기 넣은 방은 나를 부르지 않아도 정한 시각 요약과 하루 정리에 늘 내용까지 요약해요. " +
         "원문 줄을 그대로 보여 주지 않고 AI가 요점을 풀어 쓰고, 앞서 요약한 내용과 이어서 무엇이 정해지고 달라졌는지를 정리해요. " +
         "읽기만 하는 동학년방·공지방처럼 놓치기 싫은 방을 넣어 두세요. '답할 것'에는 지금처럼 나를 부른 말만 올라가요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📌 놓치기 싫은 방 ${pinned.size}곳", style = MaterialTheme.typography.titleMedium)
            if (pinned.isEmpty()) Text("아직 없어요. 아래에서 방을 골라 넣어 주세요.", style = MaterialTheme.typography.bodySmall)
            pinned.forEach { (pkg, room) ->
                key(pkg, room) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(pkg, 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text(room, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            Text(vm.appNameOf(pkg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.unpinRoom(pkg, room) }) { Text("빼기") }
                    }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 방 넣기", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("방 이름 찾기") })
            val word = q.trim()
            val list = vm.pinCandidates.filter { "${it.pkg}|${it.room}" !in pinnedKeys && (word.isEmpty() || it.room.contains(word, ignoreCase = true)) }
            if (vm.pinCandidates.isEmpty()) Text("아직 알림이 온 방이 없어요. 알림이 오면 여기에 방이 보여요.", style = MaterialTheme.typography.bodySmall)
            else if (list.isEmpty()) Text("찾는 방이 없어요.", style = MaterialTheme.typography.bodySmall)
            else if (word.isEmpty()) Text("최근에 알림이 온 방부터 보여요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.take(if (word.isEmpty()) 20 else 50).forEach { r ->
                key(r.pkg, r.room) {
                    Row(Modifier.fillMaxWidth().clickable { vm.pinRoom(r.pkg, r.room) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(r.pkg, 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text(r.room, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            Text(r.app + " · 마지막 알림 " + SimpleDateFormat("M/d", Locale.KOREA).format(r.last) +
                                (if (!vm.isRoomOn(r.pkg, r.room)) " · 꺼 둔 방(넣으면 다시 모아요)" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("＋ 넣기", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // 아직 알림이 안 온 카톡 방은 이름을 그대로 넣어 미리
            if (word.isNotEmpty() && vm.pinCandidates.none { it.room == word } && "com.kakao.talk|$word" !in pinnedKeys)
                OutlinedButton(onClick = { vm.pinRoom("com.kakao.talk", word); q = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("'$word' 이름 그대로 카톡 방으로 넣기")
                }
        }
    }
}

// ───────────────────────── 👥 사람 묶음 (설정 → 세부 화면) ─────────────────────────

@Composable
private fun PeoplePage(vm: JarvisViewModel) {
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }
    var making by rememberSaveable { mutableStateOf(false) }
    val shown = vm.groups.filterNot { it.hidden }
    Text("교직원·가족·지인처럼 내 맘대로 목록을 만들고 이모지·이름으로 꾸미세요. 이름만 쭉 넣으면 그 사람이 보낸 알림이나 이름이 나온 알림을 목록별로 모아요. 안 만들어도 요약은 그대로 돼요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (shown.isNotEmpty()) MenuGroup(shown.map { g ->
        val n = when (g.kind) {
            "students" -> vm.students.size
            "parents" -> vm.parentLinks.map { it.label }.distinct().size
            else -> vm.groupMembers[g.id]?.size ?: 0
        }
        Triple(g.title, if (n == 0) "비어 있음" else "${n}명") { vm.openGroup(g.id) }
    })
    Button(onClick = { making = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("＋ 묶음 만들기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
    }
    val hidden = vm.groups.filter { it.hidden }
    if (hidden.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("숨긴 묶음:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hidden.forEach { g -> TextButton(onClick = { vm.unhideGroup(g.id) }) { Text("${g.title} 다시 보기") } }
    }
    if (making) GroupNameDialog(title = "＋ 묶음 만들기", initEmoji = "👔", initName = "", suggest = true,
        onDone = { emoji, name -> making = false; vm.addGroup(emoji, name) }, onDismiss = { making = false })
}

/** 묶음 이름·아이콘 정하기 (만들기·이름 바꾸기 공용) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupNameDialog(title: String, initEmoji: String, initName: String, suggest: Boolean,
                            onDone: (String, String) -> Unit, onDismiss: () -> Unit) {
    var emoji by rememberSaveable { mutableStateOf(initEmoji) }
    var name by rememberSaveable { mutableStateOf(initName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggest) {
                    Text("눌러서 바로 고르기", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        People.SUGGESTIONS.forEach { (e, n) -> AssistChip(onClick = { emoji = e; name = n }, label = { Text("$e $n") }) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("묶음 이름 (예: 교직원)") })
            }
        },
        confirmButton = { Button(onClick = { onDone(emoji, name) }, enabled = name.isNotBlank()) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 묶음 한 개: 학생·학부모 묶음은 원래 화면, 직접 만든 묶음은 이름 쭉 넣기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupPage(vm: JarvisViewModel) {
    val g = vm.currentGroup ?: return
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { renaming = true }) { Text("✏️ 이름 바꾸기") }
        OutlinedButton(onClick = { removing = true }) { Text("🗑 묶음 지우기") }
    }
    when (g.kind) {
        "students" -> { vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }; StudentsCard(vm) }
        "parents" -> ParentsPage(vm)
        else -> CustomGroupCard(vm, g)
    }
    if (renaming) GroupNameDialog(title = "✏️ 이름 바꾸기", initEmoji = g.emoji, initName = g.name, suggest = false,
        onDone = { e, n -> renaming = false; vm.renameGroup(g.id, e, n) }, onDismiss = { renaming = false })
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("${g.title} 묶음을 지울까요?") },
        text = { Text(when (g.kind) {
            "students" -> "넣어 둔 학생 이름 ${vm.students.size}명도 같이 지워져요. 학생별 기록과 모은 알림은 그대로 남아요."
            "parents" -> "연결해 둔 학부모도 같이 지워져요. 모은 알림은 그대로 남아요."
            else -> "넣어 둔 이름 ${vm.groupMembers[g.id]?.size ?: 0}개도 같이 지워져요. 모은 알림은 그대로 남아요."
        }) },
        confirmButton = { Button(onClick = { removing = false; vm.removeGroup(g.id) }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("취소") } },
    )
}

/** 직접 만든 묶음: 이름만 쭉 넣기 + 최근 연락한 사람에서 고르기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomGroupCard(vm: JarvisViewModel, g: PeopleGroup) {
    LaunchedEffect(g.id) { vm.loadRecentSenders() }
    var raw by rememberSaveable(g.id) { mutableStateOf("") }
    val members = vm.groupMembers[g.id].orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 이름 넣기", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = raw, onValueChange = { raw = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예) 김교감, 박부장, 이선생 … 쉼표·줄바꿈 아무렇게나") })
            Button(onClick = { if (vm.addMembers(g.id, raw) > 0) raw = "" }, enabled = raw.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("넣기") }
            Text("알림에 뜨는 이름 그대로 넣으세요. 그 이름이 보낸 알림이나, 내용에 그 이름이 나온 알림을 이 묶음으로 모아요.",
                style = MaterialTheme.typography.bodySmall)
            val recent = vm.recentSenders.filter { it !in members }
            if (recent.isNotEmpty()) {
                Text("최근 연락한 사람에서 고르기 · 누르면 바로 들어가요", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { s -> AssistChip(onClick = { vm.addMembers(g.id, s) }, label = { Text("+ $s") }) }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${g.title} ${members.size}명", style = MaterialTheme.typography.titleMedium)
            if (members.isEmpty()) Text("아직 없어요. 위에 이름을 넣어 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { n -> InputChip(selected = true, onClick = { vm.removeMember(g.id, n) }, label = { Text("$n  ✕") }) }
            }
        }
    }
}

/**
 * 요약 탭: 내가 만든 👥 사람 묶음마다 카드 하나. 제목은 묶음의 이모지·이름 그대로(예: "👔 교직원 연락").
 * 오늘 그 묶음 사람이 보낸 연락을 사람별로 모아 최근 연락 먼저 보여 줌. 카드를 누르면 묶음 화면으로.
 */
@Composable
private fun PeopleCards(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val today = vm.peopleToday.associate { (g, l) -> g.id to l }
    vm.groups.filter { !it.hidden && (it.kind == "custom" || it.kind == "parents") }.forEach { g ->
        val list = today[g.id].orEmpty()
        val byPerson = list.groupBy { it.first }.toList()
            .sortedByDescending { (_, l) -> l.maxOf { it.second.time } }
        val noOne = (if (g.kind == "parents") vm.parentLinks.size else vm.groupMembers[g.id]?.size ?: 0) == 0
        Card(Modifier.fillMaxWidth().clickable { vm.openGroup(g.id) }) {
            Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    list.isNotEmpty() -> "${g.title} 연락 ${list.size}건 · ${byPerson.size}명"
                    noOne -> "${g.title} 연락 · 아직 넣은 사람이 없어요"
                    else -> "${g.title} 연락 · 오늘 처리할 연락이 없어요"
                }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                if (noOne) Text("눌러서 이름을 넣으면 어떤 앱으로 와도 이 묶음 연락으로 모아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                byPerson.forEach { (name, l) ->
                    val recent = l.sortedByDescending { it.second.time }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name + if (l.size > 1) " · ${l.size}건" else "", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text(timeFmt.format(recent.first().second.time), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    recent.take(2).reversed().forEach { (_, n) ->
                        Text(n.text.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * 👪 학부모 명부: 이름만 쭉 넣으면 끝. 쉼표·줄바꿈·띄어쓰기, 관계·번호 있든 없든 알아서 나눔.
 * 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 들어간 알림을 그 학부모 연락으로 자동 정리.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentsPage(vm: JarvisViewModel) {
    LaunchedEffect(Unit) { vm.loadRecentSenders() }
    var raw by rememberSaveable { mutableStateOf("") }

    Text("학부모 이름을 쭉 넣기만 하세요. 쉼표·줄바꿈 아무렇게나 써도 돼요. 문자·카톡·밴드 등 어떤 앱이든 그 이름이 들어간 알림을 학부모 연락으로 모아 답장까지 추천해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 이름 넣기", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = raw, onValueChange = { raw = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예) 민준맘, 이서윤 아버님, 박지호 어머니, 김영희") })
            Button(
                onClick = { if (vm.addParentsRaw(raw) > 0) raw = "" },
                enabled = raw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("넣기") }
            Text("알림에 뜨는 이름 그대로 넣으면 돼요. '맘·아빠' 같은 관계는 있으면 같이 알아보고, 우리 반 학생 이름이 들어 있으면 그 학생 학부모로 묶어요.",
                style = MaterialTheme.typography.bodySmall)
            if (vm.recentSenders.isNotEmpty()) {
                Text("최근 연락한 사람에서 고르기 · 누르면 바로 들어가요", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.recentSenders.forEach { s -> AssistChip(onClick = { vm.addParentsRaw(s) }, label = { Text("+ $s") }) }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val groups = vm.parentLinks.groupBy { it.label }
            Text("👪 명부 ${groups.size}명", style = MaterialTheme.typography.titleMedium)
            if (groups.isEmpty()) Text("아직 없어요. 위에 이름을 넣어 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 2026-11-21 선생님: 한 사람씩 줄로 세우면 13명이 너무 길다 → 이름 칩을 옆으로 이어 붙이고, 누르면 작은 창에서 자세히·지우기
            var picked by rememberSaveable { mutableStateOf<String?>(null) }
            if (groups.isNotEmpty()) Text("이름을 누르면 연결한 이름·번호를 보고 지울 수 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (label, links) ->
                    val student = links.first().student.takeIf { it in vm.students }
                    AssistChip(onClick = { picked = label },
                        label = { Text((if (student != null) "🧒 " else "") + label + if (links.size > 1) " · ${links.size}" else "") })
                }
            }
            picked?.let { label ->
                val links = groups[label].orEmpty()
                if (links.isEmpty()) { picked = null; return@let }
                val student = links.first().student.takeIf { it in vm.students }
                AlertDialog(
                    onDismissRequest = { picked = null },
                    title = { Text("👪 $label") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("이 이름·번호가 들어간 알림을 이 학부모 연락으로 모아요. ✕ 를 누르면 그것만 빠져요.", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                links.forEach { l -> InputChip(selected = true, onClick = { vm.removeParentLink(l) }, label = { Text("${l.match}  ✕") }) }
                            }
                            if (student != null) TextButton(onClick = { picked = null; vm.openStudent(student) }) { Text("🧒 $student 학생 기록 ›") }
                        }
                    },
                    confirmButton = { Button(onClick = { picked = null }) { Text("닫기") } },
                    dismissButton = { TextButton(onClick = { links.forEach { vm.removeParentLink(it) }; picked = null }) { Text("🗑 이 학부모 지우기") } },
                )
            }
        }
    }
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }
}

@Composable
private fun SystemSettings(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    SetupCard(
        done = vm.listenerEnabled, title = "알림 접근 권한",
        desc = if (vm.listenerEnabled) "허용됨" else "다른 앱의 알림을 읽으려면 허용이 필요해요",
        button = "설정 열기",
    ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    SetupCard(
        done = vm.hasModel, title = "AI 모델",
        desc = if (vm.hasModel) "${vm.installedModel?.title ?: "직접 고른 모델"} · " +
                   "${vm.summarizer.modelFile.length() / 1_000_000}MB"
               else "폰 진단 후 맞는 모델을 받아요",
        button = if (vm.hasModel) "바꾸기" else "받기", enabled = !vm.busy,
    ) { vm.openModelGuide() }
    val notiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    SetupCard(
        done = vm.notiPermission, title = "알림 표시 권한",
        desc = if (vm.notiPermission) "허용됨" else "브리핑 결과를 알림으로 받으려면 필요해요",
        button = "허용하기", enabled = !vm.notiPermission,
    ) {
        if (Build.VERSION.SDK_INT >= 33) notiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    // 🔧 문제 찾기용: 카톡 알림이 방 이름을 어디에 넣는지(메시지 내용은 없음)
    var probe by remember { mutableStateOf<List<String>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔧 카톡 알림 구조 (문제 찾기용)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("최근 카톡 알림 5개가 방 이름을 어느 칸에 넣는지 보여 줘요. 메시지 내용은 적지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { probe = NotiProbe.all(ctx) }) { Text(if (probe == null) "보기" else "새로 보기") }
            probe?.let { list ->
                if (list.isEmpty()) Text("아직 기록이 없어요. 카톡이 한 번 오면 생겨요.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        list.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

/** 알림 앱 정리: AI(또는 규칙)가 모으기/빼기를 추천 → "추천대로 적용" 한 번 또는 스위치로. */
@Composable
private fun AppCleanupCard(vm: JarvisViewModel) {
    // 2026-11-25 선생님: '알림이 안 들어오는 메시지 앱'과 '메시지·SNS'·'업무·일정' 목록이 한 줄로 이어져 같은 앱이 두 번 보이고 관리가 불편 —
    // 위에는 한 줄 요약과 추천 적용만, 목록은 묶음 칩으로 한 번에 하나씩, 알림이 안 오는 앱은 그 앱 줄 안에서 경고, 앱 추가·광고·도움말은 아래로
    val ctx = LocalContext.current
    val recs = vm.appRecs
    val imp = vm.workApps
    val silent = vm.silentApps.toMap()
    // 알림이 0건인 메시지 앱 중 목록에 아직 없는 것도 메시지·SNS 칸에 한 줄로(경고와 함께)
    val silentOnly = vm.silentApps.filter { (pkg, _) -> recs.none { it.stat.pkg == pkg } }
    val groups = listOf(0 to "💬 메시지·SNS", 1 to "💼 업무·일정", 2 to "📱 그 밖의 앱")
    fun inGroup(g: Int) = recs.filter { AppAdvisor.group(it.stat, imp) == g }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🌱 알림 앱 정리", style = MaterialTheme.typography.titleMedium)
            val kept = recs.count { it.stat.pkg !in vm.excludedApps }
            val off = recs.size - kept   // 목록에 보이는 앱만 셈(지운 앱이 '뺀 앱' 수에 남아 목록과 안 맞던 것)
            Text(when {
                    recs.isEmpty() -> "아직 모은 알림이 없어요. 알림이 쌓이면 앱을 정리해 추천해 드려요."
                    else -> "모으는 앱 ${kept}개 · 뺀 앱 ${off}개" +
                        (if (vm.adFilterOn) " · 오늘 광고 ${vm.adCount}건 거름" else "") +
                        (if (vm.aiBusy) " · AI가 살펴보는 중…" else if (recs.any { it.byAi }) " · AI 추천" else "")
                }, style = MaterialTheme.typography.bodyMedium)
            if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.listenerEnabled) Text("⚠️ 쌤톡스의 '알림 접근' 권한이 꺼져 있어요. 설정 → AI 모델·권한에서 켜 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

            // 추천 적용(처음, 또는 아직 살펴보지 않은 앱이 생겼을 때만). 직접 켜고 끈 앱은 추천으로 바꾸지 않음
            val pending = vm.newAppRecs
            if (recs.isNotEmpty() && (pending.isNotEmpty() || !vm.appsApplied)) {
                // 빼기 수는 실제로 바뀌는 것만(이미 꺼 둔 앱은 빼고)
                val drop = pending.count { !it.keep && it.stat.pkg !in vm.excludedApps }
                if (vm.appsApplied) Text("🆕 아직 살펴보지 않은 앱 ${pending.size}개: " + pending.take(3).joinToString(", ") { it.stat.app },
                    style = MaterialTheme.typography.labelLarge)
                Button(onClick = { vm.applyAppRecs() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (drop > 0) "추천대로 적용 (앱 ${drop}개 빼기)" else if (pending.isEmpty()) "이대로 정리 끝내기" else "추천대로 적용")
                }
            }

            if (recs.isNotEmpty() || silentOnly.isNotEmpty()) {
                // 묶음 칩: 한 번에 한 묶음만(칩에 앱 수 · 경고 수)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { (g, label) ->
                        val list = inGroup(g)
                        val warn = if (g == 0) list.count { it.stat.pkg in silent } + silentOnly.size else 0
                        val n = list.size + if (g == 0) silentOnly.size else 0
                        FilterChip(selected = tab == g, onClick = { tab = g },
                            label = { Text("$label $n" + if (warn > 0) " · ⚠️$warn" else "") })
                    }
                }
                Text(when (tab) {
                        0 -> "켜 둔 앱의 알림을 모아요. 단톡방은 앱마다 '방 고르기'로 뺄 수 있어요."
                        1 -> "여기 앱의 알림은 요약의 '업무·일정' 칸에 따로 정리해요(메일은 제목, 캘린더는 일정 그대로)."
                        else -> "쇼핑·은행·게임 등은 빼기를 추천해요. 끈 앱은 모으지도, 요약하지도 않아요."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                val rows = inGroup(tab)
                if (rows.isEmpty() && (tab != 0 || silentOnly.isEmpty())) Text(
                    if (tab == 1) "아직 없어요. '📱 그 밖의 앱'에서 앱을 골라 '업무·일정으로'를 누르세요." else "이 묶음에 앱이 없어요.",
                    style = MaterialTheme.typography.bodySmall)
                rows.forEach { r ->
                    key(r.stat.pkg) { AppRow(vm, r.stat.pkg, r.stat.app, r.stat.count, rec = r, group = tab, silentWarn = r.stat.pkg in silent) }
                }
                if (tab == 0) silentOnly.forEach { (pkg, name) -> key(pkg) { AppRow(vm, pkg, name, 0, rec = null, group = 0, silentWarn = true) } }
            }

            HorizontalDivider()
            // 아래: 앱 추가 · 광고 거르기 · 도움말
            OutlinedButton(onClick = { vm.openAddApps() }, modifier = Modifier.fillMaxWidth()) { Text("＋ 목록에 없는 앱 추가") }
            vm.addableApps?.let { list -> AddAppDialog(vm, list) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🚫 광고 자동 거르기", style = MaterialTheme.typography.labelLarge)
                    Text(if (vm.adFilterOn) "(광고)·수신거부·할인·쿠폰 같은 문자·카톡은 모으지 않아요. 등록한 사람·키워드는 거르지 않아요"
                         else "꺼짐 · 광고성 문자·카톡도 모아요", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.adFilterOn, onCheckedChange = { vm.setAdFilter(it) })
            }
            var help by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { help = !help }, contentPadding = PaddingValues(0.dp)) {
                Text(if (help) "ℹ️ 도움말 접기" else "ℹ️ 목록에 어떤 앱이 나오나요?", style = MaterialTheme.typography.labelLarge)
            }
            if (help) Text("폰에 알림이 온 앱(알림 접근을 켤 때 알림창에 있던 것 포함)이 나와요. 처음 정리하기 전에는 아직 알림이 없는 설치된 앱도 " +
                "이름을 보고 미리 추천해요. 모든 앱 알림을 기본으로 모으고, 뺄 앱만 끄면 돼요. 카톡·문자·밴드 같은 메시지 앱은 그 앱의 알림을 " +
                "켜 두어야 쌤톡스가 모을 수 있어요.", style = MaterialTheme.typography.bodySmall)
            if (recs.isEmpty()) OutlinedButton(onClick = { vm.skipAppSetup() }) { Text("나중에 할게요") }
        }
    }
}

/** 알림 앱 정리의 앱 한 줄: 아이콘·이름·건수, 모으기 스위치, 묶음에 맞는 할 일(방 고르기·업무로 옮기기), 알림이 안 오면 그 자리에서 경고 */
@Composable
private fun AppRow(vm: JarvisViewModel, pkg: String, name: String, count: Int, rec: AppRec?, group: Int, silentWarn: Boolean) {
    val ctx = LocalContext.current
    val keepNow = pkg !in vm.excludedApps
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(pkg, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(name + if (count > 0) " · ${count}건" else "", style = MaterialTheme.typography.labelLarge)
            if (rec != null) Text((if (rec.keep) "추천: 모으기 — " else "추천: 빼기 — ") + rec.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (rec.keep) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
            if (silentWarn) {
                Text(if (pkg == "com.kakao.talk") "⚠️ 7일간 0건 — 카톡 알림이 꺼져 있거나, PC 카톡을 쓰는 중이라 폰 알림이 멈춰 있을 수 있어요(PC 카톡 → 설정 → 알림 → 'PC 사용 중 모바일 알림 끄기' 해제)."
                     else "⚠️ 7일간 0건 — 이 앱의 알림이 꺼져 있으면 모을 수 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Row {
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)) } },
                        contentPadding = PaddingValues(0.dp)) { Text("알림 설정 열기", style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { vm.dismissSilent(pkg) }, contentPadding = PaddingValues(0.dp)) { Text("안 써요 · 경고 숨기기", style = MaterialTheme.typography.labelLarge) }
                }
            }
            when {
                group == 0 && keepNow -> {
                    val off = vm.excludedRoomCount(pkg)
                    TextButton(onClick = { vm.openRooms(pkg, name) }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (off > 0) "💬 방 고르기 (뺀 방 ${off}개) ›" else "💬 방 고르기 ›", style = MaterialTheme.typography.labelLarge)
                    }
                }
                group == 1 -> TextButton(onClick = { vm.setWork(pkg, false) }, contentPadding = PaddingValues(0.dp)) {
                    Text("업무·일정에서 빼기", style = MaterialTheme.typography.labelLarge)
                }
                group == 2 && keepNow -> TextButton(onClick = { vm.setWork(pkg, true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("💼 업무·일정으로", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Switch(checked = keepNow, onCheckedChange = { vm.setAppKeep(pkg, it) })
    }
}

/** 🧹 저장 공간: 보관 기간 고르기 + 지금 정리하기. 기간이 지난 기록은 자동으로 지워짐. */
@Composable
private fun StoragePage(vm: JarvisViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    Text("쌓인 기록은 아래 보관 기간이 지나면 저절로 지워져요. 앱을 열지 않아도 브리핑이 끝날 때마다 정리하고, " +
         "일주일에 한 번은 지운 자리까지 줄여 용량을 돌려받아요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val u = vm.storageUsage
            Text("💾 지금 쓰는 공간 " + (u?.let { Storage.size(it.bytes) } ?: "…"),
                style = MaterialTheme.typography.titleMedium)
            if (u != null) Text("받은 알림 ${u.noti}건 · 브리핑 ${u.briefings}개 · 학생 기록 ${u.logs}건 · 할 일 ${u.tasks}개",
                style = MaterialTheme.typography.bodySmall)
            Text("AI 모델 파일(수 GB)은 요약에 꼭 필요해서 여기서 지우지 않아요.", style = MaterialTheme.typography.labelSmall)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⏳ 보관 기간", style = MaterialTheme.typography.titleMedium)
            RetentionRow("📨 받은 알림 원문", "브리핑 세부 내역의 '원래 알림'도 이 기간만큼 볼 수 있어요", Storage.NOTI_CHOICES, vm.notiDays) {
                vm.setRetention("noti_days", it) }
            RetentionRow("📚 지난 요약", "요약 결과라 작아요", Storage.BRIEF_CHOICES, vm.briefDays) { vm.setRetention("brief_days", it) }
            RetentionRow("🧒 학생별 기록", "상담·생활기록에 쓰려면 길게 두세요", Storage.LOG_CHOICES, vm.logDays) { vm.setRetention("log_days", it) }
            Text("📌 끝낸 할 일은 30일, 마감이 60일 지난 할 일은 저절로 지워요. 앱·방 이름 목록은 180일 동안 안 오면 지워요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = { vm.cleanNow() }, enabled = !vm.cleaning, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (vm.cleaning) "정리하는 중…" else "🧹 지금 정리하기")
    }
    OutlinedButton(onClick = { confirmClear = true }, enabled = !vm.cleaning, modifier = Modifier.fillMaxWidth()) {
        Text("받은 알림 원문 모두 지우기")
    }
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("받은 알림 원문을 모두 지울까요?") },
        text = { Text("모은 알림 ${vm.storageUsage?.noti ?: 0}건이 지워지고 되돌릴 수 없어요. 지난 요약·학생 기록·할 일·설정은 그대로 남아요. 아직 정리 안 한 알림도 지워져요.") },
        confirmButton = { Button(onClick = { confirmClear = false; vm.clearNotis() }) { Text("모두 지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionRow(title: String, hint: String, choices: List<Int>, value: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { d -> FilterChip(selected = value == d, onClick = { onPick(d) }, label = { Text(Storage.label(d)) }) }
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** ＋ 앱 추가: 폰에 설치된 앱 중 아직 목록에 없는 앱 (메시지 앱 먼저) */
@Composable
private fun AddAppDialog(vm: JarvisViewModel, apps: List<JarvisViewModel.AddableApp>) {
    var q by rememberSaveable { mutableStateOf("") }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val sameName = remember(apps) { apps.groupingBy { it.name }.eachCount() }
    AlertDialog(
        onDismissRequest = { vm.closeAddApps() },
        title = { Text("＋ 앱 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("추가한 앱은 알림이 오면 바로 모아요. 그 앱의 알림이 켜져 있어야 해요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("앱 이름 찾기") })
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    // 찾는 중이면 전부에서, 아니면 추천(메시지·SNS → 알림 보내는 앱) 먼저, 그 밖의 앱은 접어 둠
                    val searching = q.isNotBlank()
                    val shown = apps.filter { !searching || it.name.contains(q.trim(), ignoreCase = true) }   // 'gmail' 로 Gmail 이 안 찾아지던 것
                    if (apps.isEmpty()) Text("앱 목록을 불러오는 중이에요…", Modifier.padding(vertical = 12.dp))
                    else if (shown.isEmpty()) Text("찾는 앱이 없어요.", Modifier.padding(vertical = 12.dp))
                    val titles = listOf("⭐ 추천 · 💬 메시지·SNS", "⭐ 추천 · 💼 업무·일정", "🔔 알림을 보내는 앱", "📱 그 밖의 앱")
                    shown.groupBy { it.group }.toSortedMap().forEach { (g, list) ->
                        if (g == 3 && !searching) {
                            TextButton(onClick = { showOthers = !showOthers }, contentPadding = PaddingValues(0.dp)) {
                                Text("${titles[g]} ${list.size}개 " + if (showOthers) "▾ 접기" else "▸ 펼치기", style = MaterialTheme.typography.labelLarge)
                            }
                            if (!showOthers) return@forEach
                        } else Text(titles[g], style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        list.forEach { a ->
                            Row(Modifier.fillMaxWidth().clickable { vm.addApp(a.pkg, a.name) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppIcon(a.pkg, 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(a.name)
                                    // 이름이 같은 앱(캘린더 둘 등)은 만든 곳으로 가른다
                                    if (sameName.getOrDefault(a.name, 0) > 1) Text(appMaker(a.pkg), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("＋ 추가", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeAddApps() }) { Text("닫기") } },
    )
}

// ───────────────────────── 🧒 우리 반 (설정) ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudentsCard(vm: JarvisViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.groups.firstOrNull { it.id == "students" }?.title ?: "🧒 우리 반 학생", style = MaterialTheme.typography.titleMedium)
            Text("학생 이름을 넣어 두면 연락에 \"민준이\"처럼 이름만 나와도 알아보고, 학생별 기록에 계속 쌓아요 (보관 기간은 설정 → 🧹 저장 공간). " +
                 "학생 이름은 중요 키워드처럼 브리핑 맨 위에 모여요.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    placeholder = { Text("김민준, 이서윤 … 한 번에 붙여 넣기") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(onClick = { vm.addStudents(input); input = "" }, enabled = input.isNotBlank()) { Text("추가") }
            }
            if (vm.students.isEmpty()) Text("아직 명부가 비어 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("학생 ${vm.students.size}명 · 눌러서 👪 학부모 연결·기록 보기", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.students.forEach { name ->
                        val n = vm.studentCounts[name] ?: 0
                        InputChip(selected = n > 0, onClick = { vm.openStudent(name) },
                            label = { Text(if (n > 0) "$name · $n" else name) },
                            trailingIcon = { Text("✕", Modifier.clickable { vm.removeStudent(name) }.padding(4.dp)) })
                    }
                }
            }
        }
    }
}

/** 학생별 기록: 날짜순으로 연락 (분류 함께). */
@Composable
private fun StudentDialog(vm: JarvisViewModel, name: String, entries: List<StudentEntry>) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    AlertDialog(
        onDismissRequest = { vm.closeStudent() },
        title = { Text("🧒 $name") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ParentLinkSection(vm, name)
                HorizontalDivider()
                Text("📒 기록 ${entries.size}건", style = MaterialTheme.typography.titleMedium)
                if (entries.isEmpty()) Text("아직 기록이 없어요. 이 학생 이름이 들어간 연락이나 연결한 학부모의 연락이 오면 여기에 쌓여요.")
                entries.forEach { e ->
                    val cat = ParentAdvisor.classify(listOf(NotiItem(0, "", e.app, e.sender, e.text, e.time, e.room)))
                    Column {
                        Text("${fmt.format(e.time)} · ${e.room} · ${cat.label}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text((if (e.sender != e.room) "${e.sender}: " else "") + e.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeStudent() }) { Text("닫기") } },
    )
}

/**
 * 👪 학부모 연결: 이름·번호와 관계를 넣거나, 최근 연락한 사람을 눌러 바로 연결.
 * 연결하면 문자·카톡·밴드·하이클래스·학교종이 어디서 온 연락이든 이 학생의 학부모 연락으로 정리됨.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentLinkSection(vm: JarvisViewModel, student: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("어머님") }
    val mine = vm.parentLinks.filter { it.student == student }
    Text("👪 학부모 연결", style = MaterialTheme.typography.titleMedium)
    Text("연결하면 문자·카톡·밴드·하이클래스·학교종이 어디서 온 연락이든 \"${if (student.length == 3) student.substring(1) else student} $relation\" 연락으로 정리돼요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (mine.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mine.forEach { l -> InputChip(selected = true, onClick = { vm.removeParentLink(l) }, label = { Text("${l.relation} · ${l.match}  ✕") }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("어머님", "아버님", "보호자").forEach { r -> FilterChip(selected = relation == r, onClick = { relation = r }, label = { Text(r) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text("이름·번호 (예: 민준맘, 010-1234-5678)") }, modifier = Modifier.weight(1f))
        Button(onClick = { vm.addParentLink(student, relation, input); input = "" }, enabled = input.isNotBlank()) { Text("연결") }
    }
    if (vm.recentSenders.isNotEmpty()) {
        Text("최근 연락한 사람에서 고르기 ($relation)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.recentSenders.forEach { s -> AssistChip(onClick = { vm.addParentLink(student, relation, s) }, label = { Text("+ $s") }) }
        }
    }
}

// ───────────────────────── 🏫 수업 중 · 🌙 퇴근 모드 (설정) ─────────────────────────


/** 💾 백업·복원: 자동 백업 상태 + 지금 백업 / 파일로 내보내기 / 파일에서 불러오기 */
@Composable
private fun BackupCard(vm: JarvisViewModel) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importer = rememberLauncherForActivityResult(OpenInBackupFolder()) { uri -> if (uri != null) vm.restoreBackup(uri) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💾 백업·복원", style = MaterialTheme.typography.titleMedium)
            Text("앱을 지웠다 다시 깔아도 명부·학부모 연결·키워드·방·모아서 알림 시각·직접 넣은 할 일이 그대로 돌아와요. " +
                 "메시지 내용과 학생별 기록은 넣지 않아요. 백업 파일도 폰 밖으로 나가지 않아요.",
                style = MaterialTheme.typography.bodySmall)
            Text(
                if (vm.lastBackup > 0) "마지막 백업: ${fmt.format(vm.lastBackup)} · 하루 한 번 다운로드/쌤톡스 폴더에 자동 저장"
                else "아직 백업이 없어요. 비밀번호를 정하면 하루 한 번 다운로드/쌤톡스 폴더에 자동으로 저장해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            // 🔒 백업 비밀번호: 백업 파일을 이 비밀번호로 암호화 (학생·학부모 이름·번호 보호)
            var pinInput by rememberSaveable { mutableStateOf("") }
            var changing by rememberSaveable { mutableStateOf(false) }
            if (!vm.hasBackupPin || changing) {
                Text(if (vm.hasBackupPin) "🔒 새 백업 비밀번호" else "🔒 먼저 백업 비밀번호를 정해 주세요 (6자 이상, 글자·숫자)",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pinInput, onValueChange = { pinInput = it.filterNot { c -> c.isWhitespace() }.take(20) },
                        singleLine = true, placeholder = { Text("6자 이상") }, modifier = Modifier.weight(1f),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                    Button(onClick = { vm.setBackupPin(pinInput); pinInput = ""; changing = false },
                        enabled = pinInput.length >= BackupCrypto.MIN_LEN) { Text("정하기") }
                }
                Text("새 폰에서 불러올 때 이 비밀번호가 필요해요. 꼭 기억해 두세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (vm.weakPin) "⚠️ 백업 비밀번호가 짧아요 · 6자 이상으로 바꿔 주세요" else "🔒 백업 비밀번호 설정됨 · 백업 파일은 암호화돼요",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (vm.weakPin) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Unspecified)
                TextButton(onClick = { changing = true }) { Text("바꾸기") }
            }

            // 📤 다른 폰으로 보내기: 카톡 나에게·드라이브·메일 등으로 백업 파일 보내기
            val ctx = LocalContext.current
            Button(onClick = { ctx.startActivity(BackupShare.shareIntent(ctx)) }, modifier = Modifier.fillMaxWidth(),
                enabled = vm.hasBackupPin) {
                Text("📤 다른 폰으로 보내기", style = MaterialTheme.typography.titleMedium)
            }
            Text("새 폰에서 받은 '쌤톡스_백업.json'을 열고 쌤톡스를 고르면 바로 불러와요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.backupNow() }, enabled = vm.hasBackupPin) { Text("지금 백업") }
                OutlinedButton(onClick = { export.launch("쌤톡스_백업.json") }, enabled = vm.hasBackupPin) { Text("파일로 내보내기") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("📥 백업 파일 골라 불러오기") }
            }
            if (vm.status.startsWith("💾") || vm.status.startsWith("불러오지") || vm.status.startsWith("백업"))
                Text(vm.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}


private fun roomLast(vm: JarvisViewModel, room: String) = vm.rooms.firstOrNull { it.name == room }?.last ?: 0L

/** 방 고르기: 이 앱에서 알림이 온 적 있는 모든 방(최근 많은 순) + 직접 추가한 방, 스위치로 켜고 끄기. */
@Composable
private fun RoomPickerDialog(vm: JarvisViewModel, pkg: String, name: String) {
    AlertDialog(
        onDismissRequest = { vm.closeRooms() },
        title = { Text("$name · 방 고르기") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("방을 누르거나 스위치로 켜고 꺼요. 끈 방은 모으지도, 요약하지도 않아요. 단, 중요 키워드·등록한 사람은 모아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("단톡방은 나를 부르거나 멘션한 말만 요약해요. 나를 부르지 않아도 꼭 볼 방은 📌 놓치기 싫은 방으로 정해 두세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ＋ 방 이름 직접 추가: 다른 앱은 카톡 방 목록을 읽을 수 없어서, 아직 알림이 안 온 방은 이름으로 미리 넣음
                var newRoom by rememberSaveable { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newRoom, onValueChange = { newRoom = it }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("방 이름 직접 추가") })
                    OutlinedButton(onClick = { vm.addRoom(pkg, newRoom); newRoom = "" }, enabled = newRoom.isNotBlank()) { Text("추가") }
                }
                Text("한 번이라도 알림이 온 방은 모두 여기 남아요. 아직 알림이 안 온 방은 이름을 그대로 넣으면 미리 켜고 끌 수 있어요.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.rooms.isEmpty()) Text("아직 알림이 온 방이 없어요. 위에서 방 이름을 넣어 추가할 수 있어요.", Modifier.padding(vertical = 12.dp))
                else Row {
                    TextButton(onClick = { vm.setAllRooms(pkg, true) }) { Text("모두 켜기") }
                    TextButton(onClick = { vm.setAllRooms(pkg, false) }) { Text("모두 끄기") }
                }
                vm.rooms.forEach { (room, count) ->
                    val on = vm.isRoomOn(pkg, room)
                    // 방 줄을 눌러도 모으기 켜기·끄기
                    Row(Modifier.fillMaxWidth().clickable { vm.setRoom(pkg, room, !on) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            Text(when {
                                    count > 0 -> "최근 7일 ${count}건"
                                    roomLast(vm, room) > 0 -> "마지막 알림 " + SimpleDateFormat("M/d", Locale.KOREA).format(roomLast(vm, room))
                                    else -> "직접 추가한 방"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = on, onCheckedChange = { vm.setRoom(pkg, room, it) })
                    }
                    // 📌 놓치기 싫은 방(2026-11-26: 방마다 '저절로·늘 중요·한 줄·건수만' 네 가지를 고르던 것을 하나로 줄임)
                    if (on) Row(Modifier.padding(bottom = 6.dp)) {
                        val pinned = vm.roomTier(pkg, room) == Relevance.ME
                        FilterChip(selected = pinned, onClick = { if (pinned) vm.unpinRoom(pkg, room) else vm.pinRoom(pkg, room) },
                            label = { Text("📌 놓치기 싫은 방", style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeRooms() }) { Text("완료") } },
    )
}

/** 🙋 나를 부르는 말: 이름·별명·닉네임. 카톡의 내 이름·⭐ 키워드를 추천으로 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MyNamesCard(vm: JarvisViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🙋 나를 부르는 말", style = MaterialTheme.typography.titleMedium)
            Text("단톡방에서 나를 부르거나 멘션할 때 쓰는 이름·별명·닉네임이에요. 이 말이 나온 대화는 '나에게 온 대화'로 내용까지 요약하고, " +
                "그 사람이 나에게 묻거나 부탁하면 🙋 답할 것에 올려요. 나를 부르지 않은 단톡방은 한 줄이나 이름·건수로만 짧게 보여요.",
                style = MaterialTheme.typography.bodySmall)
            Text("띄어 쓴 별명('민수 쌤')은 붙여 써도('@민수쌤') 알아봐요. 우리 반 학생 이름이 나온 대화도 나에게 온 대화로 봐요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.myNames.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.myNames.forEach { n -> InputChip(selected = true, onClick = { vm.removeMyName(n) }, label = { Text("$n  ✕") }) }
            }
            val recs = remember(vm.myNames) { vm.myNameSuggestions() }
            if (recs.isNotEmpty()) {
                Text("눌러서 추가 (카톡에 찍힌 내 이름 · 중요 키워드)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recs.take(12).forEach { w -> AssistChip(onClick = { vm.addMyName(w) }, label = { Text("+ $w") }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("예: 김민수, 민수쌤") }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addMyName(input); input = "" }, enabled = input.trim().length >= 2) { Text("추가") }
            }
        }
    }
    val ctxToMe = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🧭 쓸수록 정확해져요", style = MaterialTheme.typography.titleSmall)
            Text("이름이 없어도 내 말에 이어진 답, 나를 부른 사람이 이어서 한 말, 내가 자주 답하는 사람의 물음을 점수로 따져 나에게 한 말을 찾아요. " +
                 "밤에 폰을 쓰지 않을 때 지난 하루 대화를 보고(내가 답한 말, 애매한 말은 폰 안의 AI가 판단) 이 점수를 저절로 다듬고, 사람들이 나를 부르는 말도 찾아 위에 추천해요. 폰 밖으로는 나가지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(remember(vm.myNames) { ToMe.status(ctxToMe) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 중요 키워드: 추천 칩을 누르거나 직접 입력. 들어간 알림은 브리핑 맨 위 ⭐에 따로 모임. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun KeywordCard(vm: JarvisViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⭐ 중요 키워드", style = MaterialTheme.typography.titleMedium)
            Text("이 단어가 들어간 알림은 브리핑 맨 위에 따로 모아 보여 줘요. 끈 방에서 와도 놓치지 않아요.",
                style = MaterialTheme.typography.bodySmall)
            if (vm.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.keywords.forEach { w ->
                    InputChip(selected = true, onClick = { vm.removeKeyword(w) }, label = { Text("$w  ✕") })
                }
            }
            // 최근 알림에서 찾은 추천 (규칙 + AI). 없으면 기본 추천 단어
            val recs = vm.keywordRecs.filter { it.first !in vm.keywords }
            if (recs.isNotEmpty()) {
                Text(if (vm.aiBusy) "🌱 최근 알림에서 찾은 추천 · AI가 더 살펴보는 중…" else "🌱 최근 알림에서 찾은 추천",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recs.forEach { (w, n) -> AssistChip(onClick = { vm.addKeyword(w) }, label = { Text("+ $w · ${n}번") }) }
                }
                OutlinedButton(onClick = { vm.addAllKeywordRecs() }) { Text("추천 모두 추가") }
            }
            val suggestions = AppFilter.SUGGESTED_KEYWORDS.filter { it !in vm.keywords && recs.none { r -> r.first == it } }
            if (recs.isEmpty() && suggestions.isNotEmpty()) {
                Text("눌러서 추가", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { w -> AssistChip(onClick = { vm.addKeyword(w) }, label = { Text("+ $w") }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("직접 입력 (예: 학생 이름)") }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addKeyword(input); input = "" }, enabled = input.isNotBlank()) { Text("추가") }
            }
        }
    }
}

@Composable
private fun SetupCard(
    done: Boolean, title: String, desc: String, button: String,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (done) "✅ " else "⬜ ") + title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}

/**
 * 🍎 애플 앱 같은 움직임: 시간 고정(tween) 대신 튕기지 않는 스프링.
 * 손을 떼는 순간처럼 빠르게 시작해 자연스럽게 멈춤. 크기·위치·투명도 모두 같은 느낌으로.
 */
internal object Motion {
    val fade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val fadeFast = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val size = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val offset = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** 폰에 깔린 앱의 실제 아이콘(없으면 📱). 한 번 그린 것은 다시 읽지 않음 */
@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    // 아이콘 읽기는 앱마다 수 ms — 목록이 길면 화면이 멈칫해서 한 번 읽은 것은 모아 두고, 처음 읽기는 화면 밖(IO)에서.
    // key(pkg): 같은 자리에 다른 앱이 오면(묶음 칩 바꾸기·추천 적용으로 순서가 바뀜) 상태를 새로 — 예전엔 앞 앱의 아이콘이 남아
    // 앱과 아이콘이 엇갈렸다(2026-11-25 선생님: '앱과 아이콘이 불일치가 심함')
    key(pkg) {
        val bmp by produceState(IconCache.peek(pkg)) {
            if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { IconCache.load(ctx, pkg) }
        }
        if (bmp != null) androidx.compose.foundation.Image(bmp!!, contentDescription = null, modifier = Modifier.size(size))
        else Box(Modifier.size(size), contentAlignment = Alignment.Center) { Text("📱") }
    }
}

private object IconCache {
    private val map = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(80)
    fun peek(pkg: String) = map.get(pkg)
    fun load(ctx: android.content.Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    }.getOrNull()?.also { map.put(pkg, it) }
}

/** 이름이 같은 앱을 가르는 '만든 곳' (com.google.android.calendar → Google · 캘린더) */
fun appMaker(pkg: String): String {
    val maker = when {
        pkg.startsWith("com.google.") -> "Google"
        pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") -> "삼성"
        pkg.startsWith("com.microsoft.") -> "Microsoft"
        pkg.startsWith("com.naver.") || pkg.startsWith("com.nhn.") -> "네이버"
        pkg.startsWith("com.kakao.") -> "카카오"
        pkg.startsWith("com.lge.") -> "LG"
        else -> pkg.split('.').getOrNull(1)?.replaceFirstChar { it.uppercase() } ?: pkg
    }
    return "$maker 제공 · $pkg"
}

// ───────────────────────── 🔕 모아서 알림 (v1.8.0 단순화) ─────────────────────────

/**
 * 2026-11-21 선생님: "기능이 너무 많으면 오히려 불편 — 단순하게".
 * 스위치 하나: 켜면 모으는 앱 알림은 늘 바로 숨기고 모아 두었다가 정한 때 요약해서 알림, 끄면 평소처럼 오고 요약은 직접.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulePage(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val rev = vm.quietRev
    if (rev < 0) return
    val sc = vm.schedule
    val dnd = vm.dndGranted()
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    fun pick(start: Int, onPick: (Int) -> Unit) = TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, start / 60, start % 60, true).show()

    // 큰 스위치 하나
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔕 모아서 알림", style = MaterialTheme.typography.titleLarge)
                    Text(if (sc.digest) "켜짐 · " + vm.nextRunText() else "꺼짐 · 알림은 평소처럼 오고, 요약은 📋 요약 탭에서 직접 눌러요.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                // 진한(회갈색) 카드 위라 켜짐 막대가 카드 색과 같아 점만 보이던 것 → 크림 막대 + 회갈색 점
                Switch(checked = sc.digest, onCheckedChange = { vm.setDigest(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ssam.Canvas, checkedThumbColor = Ssam.Stone))
            }
            if (sc.digest) Text(
                "모으는 앱(🌱 알림 앱 정리에서 켠 앱)의 알림은 도착하자마자 상태바에서 사라지고 쌤톡스에 모여요. 아래 정한 때 한 번에 요약해서 알려 드려요. " +
                "전화·알람·재난 문자·인증번호는 언제나 그대로 와요.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    // ⏰ 알람 및 리마인더 권한이 없으면 정한 시각이 몇 분 밀릴 수 있음(Play 스토어 정책으로 사용자가 켜는 권한)
    if (!vm.exactAlarm && (sc.digest || sc.dailyOn)) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⏰ 정한 시각에 딱 맞추려면", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("'알람 및 리마인더'를 허용해 주세요. 허용하지 않으면 요약·하루 정리가 몇 분 늦게 올 수 있어요.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { ctx.startActivity(exactAlarmSettings(ctx)) }) { Text("허용하러 가기") }
        }
    }
    // 🎯 중요한 대화만 요약 (IDENTITY.md 원칙 2 — 온 연락은 간단한 내용까지, 많으면 핵심 요약)
    // 🗓 하루 정리 (모아서 알림을 꺼도 따로)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🗓 하루 정리", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                    Text(if (sc.dailyOn) "매일 ${hm(sc.dailyMinute)}에 오늘 요약들을 한 번에 모아 알려 드려요." else "꺼짐",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = sc.dailyOn, onCheckedChange = { vm.setDaily(it) })
            }
            if (sc.dailyOn) {
                AssistChip(onClick = { pick(sc.dailyMinute) { vm.setDailyTime(it) } }, label = { Text("⏰ ${hm(sc.dailyMinute)} 바꾸기") })
                Text("그날 요약들을 AI가 다시 훑어 ⭐ 중요 키워드·업무·일정 → 학생·학부모·마감·답장할 연락 → 대화방 순으로 골라 15줄 안쪽으로 정리해요(앱 알림은 건수만). " +
                    "그 시각에 아직 요약 안 한 알림이 있으면 먼저 요약해요. 오늘 요약이 하나도 없으면 알리지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!sc.digest) return

    // 언제 알려 줄까
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⏰ 정한 시각에 알림", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sc.times.forEach { m -> InputChip(selected = true, onClick = { vm.removeTime(m) }, label = { Text("${hm(m)}  ✕") }) }
                AssistChip(onClick = { pick(if (sc.times.isEmpty()) 8 * 60 else 12 * 60) { vm.addTime(it) } }, label = { Text("＋ 시각 더하기") })
            }
            if (sc.times.isEmpty() && sc.intervalHours == 0) AssistChip(onClick = { vm.addTime(8 * 60); vm.addTime(17 * 60) },
                label = { Text("아침 8:00 · 저녁 17:00 넣기") })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("⏱ 몇 시간마다 알림", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 3, 4).forEach { h ->
                    FilterChip(selected = sc.intervalHours == h, onClick = { vm.setInterval(h) }, label = { Text(if (h == 0) "안 함" else "${h}시간마다") })
                }
            }
            if (sc.intervalHours > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { pick(sc.intervalFrom) { vm.setIntervalFrom(it) } }, label = { Text(hm(sc.intervalFrom)) })
                Text("~")
                AssistChip(onClick = { pick(sc.intervalTo) { vm.setIntervalTo(it) } }, label = { Text(hm(sc.intervalTo)) })
                Text("사이", style = MaterialTheme.typography.bodySmall)
            }
            Text("새 알림이 없으면 알리지 않아요. 알림이 많으면 30개씩 나눠 요약한 뒤 합쳐서 알려 드려요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // 소리·쉬는 날
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔇 소리도 끄기(방해 금지)", style = MaterialTheme.typography.labelLarge)
                    Text(if (dnd) "✅ 켜 두면 모으는 동안 메시지·알림 소리와 진동이 꺼져요. 📞 전화와 알람은 그대로 울려요."
                         else "숨기기만 하면 알림이 울린 다음에 사라져서 소리는 나요. 켜면 폰 설정의 방해 금지 권한 화면으로 가요 — 쌤톡스를 허용하고 돌아오세요.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = Dnd.autoOn(ctx) && dnd, onCheckedChange = { on ->
                    if (on && !dnd) runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    vm.setDndAuto(on)
                })
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📅 주말·공휴일엔 쉬기", style = MaterialTheme.typography.labelLarge)
                    Text("켜면 토·일·공휴일(대체공휴일 포함)에는 모으지 않고 알림이 평소처럼 와요.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = sc.restSkip, onCheckedChange = { vm.setRestSkip(it) })
            }
        }
    }
    OutlinedButton(onClick = { vm.briefNow() }, enabled = vm.hasModel && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("지금 요약 받아보기 (테스트)")
    }
    // 📝 최근 자동 요약: 정한 시각에 실제로 돌았는지·결과(새 알림 없음도) 확인
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📝 최근 자동 요약", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
            if (vm.autoLog.isEmpty()) Text("아직 자동으로 돈 요약이 없어요. 정한 시각이 지나면 여기에 결과가 남아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.autoLog.take(6).forEach { e ->
                Row {
                    Text(fmt.format(e.at), Modifier.width(118.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${e.label} · ${e.result}", style = MaterialTheme.typography.bodySmall,
                        color = if (e.result.startsWith("실패")) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Unspecified)
                }
            }
            Text("새 알림이 없으면 알림은 보내지 않고 여기에만 남겨요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

