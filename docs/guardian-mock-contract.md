# 보호자 앱 연동 Mock 규격

지침서 5.2·5.5·5.8 및 보호자 앱의 `feature/guardian-app` 브랜치 `4ea1c4a`를 기준으로 맞췄습니다. 아래 예시는 양쪽 앱의 Mock용입니다. HTTP 요청·응답의 미정 항목과 기존 엔드포인트는 서버 연동 때 서버 담당자가 확인합니다.

## 연결과 호칭

가족 연결은 `links/{elderId_guardianId}` 한 문서에 한 쌍씩 저장합니다. 한 어르신을 여러 보호자에 연결하거나, 한 보호자가 여러 어르신을 연결할 수 있습니다. 하나를 해제하면 그 쌍만 두 목록에서 빠집니다.

```json
{
  "elderId": "elder-a",
  "guardianId": "guardian-a",
  "elderName": "김해봄",
  "elderLabel": "어머니",
  "guardianLabel": "큰딸",
  "connectedAt": "2026-11-26T06:00:00Z"
}
```

- `elderLabel`은 보호자 앱에서 어르신을 부르는 호칭입니다. 비어 있으면 `elderName`을 표시합니다.
- `guardianLabel`은 고령자 앱에서 보호자를 부르는 호칭입니다. 비어 있으면 보호자 이름(`guardianName`, 있을 때), 그것도 없으면 "보호자"로 표시합니다.
- **호칭 필드는 `elderLabel`·`guardianLabel` 두 개로 통일합니다.** 고령자 앱은 `relationship`을 더 이상 읽거나 쓰지 않습니다(Mock 모델에서도 제거).
- 서버에 이미 `relationship`으로 저장된 데이터가 있다면, 보호자 앱 기존 모델 주석대로 `elderLabel`로 옮기는 것을 제안합니다. 옮기는 방법과 시점은 서버 담당자가 정합니다.

## 보호자 앱 확인 요청 항목

| 확인 항목 | 고령자 앱 상태 | 확인 방법 |
| --- | --- | --- |
| 연결 코드 발급 | 구현(Mock). 6자리 문자열, 10분·1회, 재발급 시 이전 코드 무효 | 연습 화면 **6자리 연결 코드 발급**, 테스트 `codesUseSixDigits…` |
| 다대다(M:N) 연결 | 구현(Mock). 어르신 1명에 보호자 여러 명, 보호자 1명에 어르신 여러 명. 연결 1쌍 = 문서 1개 | 보호자 A·B 연결, 테스트 `multipleLinks…`, `disconnectingOneGuardian…` |
| 연결 해제 실시간 반영 | 구현(Mock). 어느 쪽에서 끊어도 그 한 쌍만 양쪽 목록에서 바로 빠짐. 고령자 쪽은 "연결을 끊을까요?" 확인 후 해제 | 연습 화면 양쪽 해제 버튼, 테스트 `multipleLinks…Disconnection` |
| 승인·거절 결과 수신 | 구현(Mock). `approvals/{id}`의 `status` 변화를 받아 반영하고, 응답한 보호자를 `guardianLabel`로 안내 ("큰딸 님이 허락했어요") | 연습 화면 승인 요청 → 보호자 보기에서 승인/거절, 테스트 `approvalResultNames…` |
| 호칭 필드 | `elderLabel`·`guardianLabel`로 통일, `relationship` 사용 안 함 | 위 "연결과 호칭" |

실제 네트워크 전달(두 기기 사이)은 서버를 연결한 뒤 함께 확인합니다.

## 요청·응답 데이터 형식 (연결·해제·승인·거절)

기존 엔드포인트와 다대다 구조는 바꾸지 않습니다. 사용자 ID는 본문에 넣지 않고 인증 토큰으로 식별합니다. 시간은 ISO 8601 UTC 문자열입니다.

### 연결

```http
POST /v1/elder/connect-codes            (#11, 고령자)
Idempotency-Key: <UUID>
{}
→ 200 { "code": "000042", "expiresAt": "2026-11-26T06:10:00Z" }
```

```http
보호자 연결 (#14, 보호자) — 경로·HTTP 본문은 기존 서버 규격을 따름
{ "code": "000042", "elderLabel": "어머니", "guardianLabel": "큰딸" }
→ 연결 결과 = 아래 links 문서 1개
```

연결되면 `links/{elderId_guardianId}`가 생기고, 고령자 앱은 `links` where `elderId == 내 uid`로 목록을 받습니다.

### 해제

```http
DELETE /v1/elder/links/{guardianId}      (#12, 고령자)
→ 200 (고령자 앱은 본문을 보지 않음. 제안: { "ok": true })

보호자 해제 (#15, 보호자) — 상대 elderId 지정, HTTP 형식은 기존 서버 규격
```

해제 = 해당 `links` 문서 삭제. 양쪽 앱은 `links` 구독으로 바로 목록에서 지웁니다. 다른 보호자와의 연결은 그대로입니다.

### 승인 요청·취소 (고령자)

```http
POST /v1/elder/approvals                 (#9)
Idempotency-Key: <UUID>
{ "packageName": "kr.go.minwon.m", "appName": "정부24", "action": "guide_gov24_auth", "category": "public" }
→ 200 { "result": "created", "approvalId": "a_789", "expiresAt": "2026-11-26T06:05:00Z" }
→ 200 { "result": "no_guardians" }

POST /v1/elder/approvals/{approvalId}/cancel   (#10)
{}
→ 200 { "status": "cancelled" }   (이미 끝난 요청이면 409/404 → 고령자 앱은 무시)
```

### 승인·거절 (보호자 #13) → 고령자 앱이 받는 것

보호자 앱의 HTTP 본문은 기존 서버 규격을 따릅니다. 고령자 앱은 `approvals/{approvalId}` 문서를 구독하고 아래 세 필드만 봅니다.

| 필드 | 값 | 고령자 앱 동작 |
| --- | --- | --- |
| `status` | `approved` / `rejected` / `expired` / `cancelled` | 안내 계속 / 멈춤 / 멈춤 / 멈춤 |
| `respondedBy` | 응답한 보호자 uid | 그 연결의 `guardianLabel`로 "큰딸 님이 허락했어요" |
| `respondedAt` | 응답 시각 | `createdAt` ~ `expiresAt` 안의 응답만 반영 |

## 기존 작업별 데이터

| 지침서 작업 | 요청 또는 Mock 입력 | 응답 또는 Mock 결과 |
| --- | --- | --- |
| #11 `POST /v1/elder/connect-codes` | 고령자 인증으로 본인을 식별. Mock 본문 없음 | Mock `{ "code": "000042", "expiresAt": "2026-11-26T06:10:00Z" }`. 코드는 문자열이며 10분·1회 사용, 재발급 시 이전 코드는 무효 |
| #14 보호자 연결 | Mock `{ "code": "000042", "elderLabel": "어머니", "guardianLabel": "큰딸" }` | 보호자 Mock이 반환하는 `FamilyLink`. 연결 생성은 양쪽 연결 목록에 반영 |
| #12 `DELETE /v1/elder/links/{guardianId}`, #15 보호자 해제 | 고령자는 경로의 `guardianId`, 보호자는 Mock 메서드의 `elderId`로 상대를 지정 | 보호자 Mock 함수는 반환값 없음. 양쪽 목록에서 해당 쌍 제거. HTTP 정상 응답 코드는 기존 서버 규격 확인 후 사용 |
| #9 `POST /v1/elder/approvals` | `{ "packageName": "kr.go.minwon.m", "appName": "정부24", "action": "guide_gov24_auth", "category": "public" }` | `{ "result": "created", "approvalId": "a_789", "expiresAt": "2026-11-26T06:05:00Z" }` 또는 `{ "result": "no_guardians" }` |
| #13 보호자 승인·거절 | 보호자 Mock의 `respondToApproval(id, approve: true/false)`. 실제 HTTP 본문은 기존 서버 규격 확인 후 사용 | Mock은 갱신된 승인 객체를 반환합니다. 고령자 앱은 `approvals/{id}`의 상태를 관찰합니다. |
| #10 `POST /v1/elder/approvals/{id}/cancel` | 경로의 승인 ID. Mock 본문 없음 | 현재 `pending` 요청이 `cancelled`가 됩니다. |

지침서의 #13~#15는 `SERVER_API.md`를 참조하지만 확인한 브랜치에는 해당 문서가 없습니다. 보호자 Mock의 메서드 인자와 반환 객체를 HTTP 규격으로 간주하면 안 됩니다. 사용자 ID는 실제 연동 때 인증으로 식별하고 시간 필드는 API에서 ISO 8601 UTC를 사용합니다.

## 승인 변경 예시

승인 ID는 문서 경로에서 받습니다. `guardianIds` 배열에는 요청 시점에 연결된 여러 보호자 ID를 담습니다.

```json
{
  "elderId": "elder-a",
  "guardianIds": ["guardian-a", "guardian-b"],
  "appName": "정부24",
  "packageName": "kr.go.minwon.m",
  "action": "guide_gov24_auth",
  "category": "public",
  "status": "approved",
  "createdAt": "2026-11-26T06:00:00Z",
  "expiresAt": "2026-11-26T06:05:00Z",
  "respondedAt": "2026-11-26T06:00:25Z",
  "respondedBy": "guardian-b"
}
```

- 상태는 `pending`, `approved`, `rejected`, `expired`, `cancelled`를 사용합니다. 승인과 거절에는 응답 시각과 응답자 ID가 있습니다.
- 만료까지 남은 시간은 5분입니다. 현재 연결된 보호자의 첫 유효 응답만 반영합니다. 요청이 종료된 뒤의 응답, 해제된 보호자의 새 응답, 이전 요청 ID로 온 결과는 안내를 재개시키지 않습니다.
- 시험판의 **보호자 연동 연습 (Mock)** 화면에서 고령자와 보호자 보기를 함께 검증할 수 있습니다. 두 보기에는 같은 기기의 메모리 데이터가 즉시 반영됩니다. 두 앱 사이의 네트워크 전달은 실제 서버 연결 때 확인합니다.
