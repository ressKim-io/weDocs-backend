# Protocol Documentation
<a name="top"></a>

## Table of Contents

- [ai/ai.proto](#ai_ai-proto)
    - [RetrieveRequest](#ai-RetrieveRequest)
    - [RetrieveResponse](#ai-RetrieveResponse)
    - [RetrievedChunk](#ai-RetrievedChunk)
  
    - [RagRetriever](#ai-RagRetriever)
  
- [common/common.proto](#common_common-proto)
    - [DocRef](#common-DocRef)
  
    - [Role](#common-Role)
  
- [crdt/crdt.proto](#crdt_crdt-proto)
    - [ClientFrame](#crdt-ClientFrame)
    - [ServerFrame](#crdt-ServerFrame)
    - [Snapshot](#crdt-Snapshot)
  
    - [CrdtEngine](#crdt-CrdtEngine)
  
- [doc/doc.proto](#doc_doc-proto)
    - [CheckPermissionRequest](#doc-CheckPermissionRequest)
    - [CheckPermissionResponse](#doc-CheckPermissionResponse)
    - [DocMeta](#doc-DocMeta)
    - [LoadSnapshotRequest](#doc-LoadSnapshotRequest)
    - [LoadSnapshotResponse](#doc-LoadSnapshotResponse)
    - [SaveSnapshotRequest](#doc-SaveSnapshotRequest)
    - [SaveSnapshotResponse](#doc-SaveSnapshotResponse)
  
    - [DocService](#doc-DocService)
  
- [Scalar Value Types](#scalar-value-types)



<a name="ai_ai-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## ai/ai.proto



<a name="ai-RetrieveRequest"></a>

### RetrieveRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |
| query | [string](#string) |  |  |
| top_k | [int32](#int32) |  |  |






<a name="ai-RetrieveResponse"></a>

### RetrieveResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| chunks | [RetrievedChunk](#ai-RetrievedChunk) | repeated |  |






<a name="ai-RetrievedChunk"></a>

### RetrievedChunk



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| chunk_id | [string](#string) |  |  |
| text | [string](#string) |  |  |
| score | [float](#float) |  |  |





 

 

 


<a name="ai-RagRetriever"></a>

### RagRetriever
내부 RAG 검색 계약. 클라이언트向 Q&amp;A/요약은 REST &#43; SSE(토큰 스트림)로 별도 — proto 아님.
(AI Service는 stateless 텍스트 in/out, CRDT 미인지 — SDD §0.3 / 가드레일)

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Retrieve | [RetrieveRequest](#ai-RetrieveRequest) | [RetrieveResponse](#ai-RetrieveResponse) |  |

 



<a name="common_common-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## common/common.proto



<a name="common-DocRef"></a>

### DocRef
여러 서비스가 공유하는 문서 참조


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |





 


<a name="common-Role"></a>

### Role
문서 권한 역할 (Doc 서비스 권한 모델)

| Name | Number | Description |
| ---- | ------ | ----------- |
| ROLE_UNSPECIFIED | 0 |  |
| ROLE_VIEWER | 1 |  |
| ROLE_EDITOR | 2 |  |
| ROLE_OWNER | 3 |  |


 

 

 



<a name="crdt_crdt-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## crdt/crdt.proto



<a name="crdt-ClientFrame"></a>

### ClientFrame
클라이언트 → 엔진 프레임


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  | 첫 프레임에서 식별(메타데이터와 일치) |
| update | [bytes](#bytes) |  | yrs/Yjs binary update |
| state_vector | [bytes](#bytes) |  | 신규 접속 시 최소 diff 계산용 |






<a name="crdt-ServerFrame"></a>

### ServerFrame
엔진 → 클라이언트 프레임


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| update | [bytes](#bytes) |  |  |
| state_vector | [bytes](#bytes) |  |  |






<a name="crdt-Snapshot"></a>

### Snapshot
encode_state_as_update 결과 blob


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |
| data | [bytes](#bytes) |  |  |





 

 

 


<a name="crdt-CrdtEngine"></a>

### CrdtEngine
CRDT 엔진: 문서별 yrs 상태 머지 / 스냅샷.
docId는 Sync 스트림의 gRPC 메타데이터로 전달 → Istio waypoint consistent-hash 라우팅
(같은 문서 = 같은 엔진 인스턴스, 인메모리 일관성).

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Sync | [ClientFrame](#crdt-ClientFrame) stream | [ServerFrame](#crdt-ServerFrame) stream | 게이트웨이 ↔ 엔진 양방향 스트림. 매 update마다 새 호출 X (연결 유지). |
| GetSnapshot | [.common.DocRef](#common-DocRef) | [Snapshot](#crdt-Snapshot) | 스냅샷 조회 (복원/디버그용). |

 



<a name="doc_doc-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## doc/doc.proto



<a name="doc-CheckPermissionRequest"></a>

### CheckPermissionRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |
| user_id | [string](#string) |  |  |






<a name="doc-CheckPermissionResponse"></a>

### CheckPermissionResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| allowed | [bool](#bool) |  |  |
| role | [common.Role](#common-Role) |  |  |






<a name="doc-DocMeta"></a>

### DocMeta



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |
| title | [string](#string) |  |  |
| owner_id | [string](#string) |  |  |
| created_at | [int64](#int64) |  | epoch millis |
| updated_at | [int64](#int64) |  | epoch millis |
| workspace_id | [string](#string) |  | page-tree (ADR-0012) |
| parent_id | [string](#string) |  | 빈 문자열 = 루트 페이지 |






<a name="doc-LoadSnapshotRequest"></a>

### LoadSnapshotRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |






<a name="doc-LoadSnapshotResponse"></a>

### LoadSnapshotResponse
스냅샷 부재(신규 페이지) = snapshot 빈 바이트 &#43; version 0 → 엔진은 빈 Doc로 시작


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| snapshot | [bytes](#bytes) |  | lib0 v1 encode_state_as_update 결과 |
| version | [int64](#int64) |  |  |






<a name="doc-SaveSnapshotRequest"></a>

### SaveSnapshotRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| doc_id | [string](#string) |  |  |
| snapshot | [bytes](#bytes) |  |  |
| version | [int64](#int64) |  |  |






<a name="doc-SaveSnapshotResponse"></a>

### SaveSnapshotResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| version | [int64](#int64) |  |  |





 

 

 


<a name="doc-DocService"></a>

### DocService
Doc/Session 서비스: 문서 메타·권한·스냅샷 영속화 (내부 gRPC).
클라이언트向 문서 CRUD·인증은 REST.

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| CheckPermission | [CheckPermissionRequest](#doc-CheckPermissionRequest) | [CheckPermissionResponse](#doc-CheckPermissionResponse) | 게이트웨이/AI가 호출하는 인가 체크 |
| SaveSnapshot | [SaveSnapshotRequest](#doc-SaveSnapshotRequest) | [SaveSnapshotResponse](#doc-SaveSnapshotResponse) | CRDT 엔진 스냅샷 영속화 오케스트레이션 (엔진 push, ADR-0013) |
| LoadSnapshot | [LoadSnapshotRequest](#doc-LoadSnapshotRequest) | [LoadSnapshotResponse](#doc-LoadSnapshotResponse) | 엔진이 doc ensure(첫 구독) 시 복원용으로 호출 (ADR-0013) |
| GetDocMeta | [.common.DocRef](#common-DocRef) | [DocMeta](#doc-DocMeta) |  |

 



## Scalar Value Types

| .proto Type | Notes | C++ | Java | Python | Go | C# | PHP | Ruby |
| ----------- | ----- | --- | ---- | ------ | -- | -- | --- | ---- |
| <a name="double" /> double |  | double | double | float | float64 | double | float | Float |
| <a name="float" /> float |  | float | float | float | float32 | float | float | Float |
| <a name="int32" /> int32 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint32 instead. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="int64" /> int64 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint64 instead. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="uint32" /> uint32 | Uses variable-length encoding. | uint32 | int | int/long | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="uint64" /> uint64 | Uses variable-length encoding. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum or Fixnum (as required) |
| <a name="sint32" /> sint32 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int32s. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sint64" /> sint64 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int64s. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="fixed32" /> fixed32 | Always four bytes. More efficient than uint32 if values are often greater than 2^28. | uint32 | int | int | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="fixed64" /> fixed64 | Always eight bytes. More efficient than uint64 if values are often greater than 2^56. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum |
| <a name="sfixed32" /> sfixed32 | Always four bytes. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sfixed64" /> sfixed64 | Always eight bytes. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="bool" /> bool |  | bool | boolean | boolean | bool | bool | boolean | TrueClass/FalseClass |
| <a name="string" /> string | A string must always contain UTF-8 encoded or 7-bit ASCII text. | string | String | str/unicode | string | string | string | String (UTF-8) |
| <a name="bytes" /> bytes | May contain any arbitrary sequence of bytes. | string | ByteString | str | []byte | ByteString | string | String (ASCII-8BIT) |

