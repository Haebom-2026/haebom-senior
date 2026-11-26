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
- `guardianLabel`은 고령자 앱에서 보호자를 부르는 호칭입니다. 비어 있으면 가용한 보호자 표시 이름을 사용합니다.
- 기존 `relationship` 값은 보존합니다. 지침서의 예시는 ‘딸·아들’을 쓰고, 보호자 앱의 기존 모델 주석은 이전 값을 `elderLabel`로 읽는다고 적혀 있습니다. 데이터가 어느 방향으로 저장됐는지 서버 담당자가 확인한 뒤 변환을 정합니다. 새 Mock 연결은 방향이 명확한 두 필드를 사용합니다.

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
