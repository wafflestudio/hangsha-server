# 행샤(Hangsha): 교내 행사 캘린더 서비스
<img width="250" height="115" alt="image" src="https://github.com/user-attachments/assets/046b4994-764e-40fd-9684-3d7d9dc11d76" />

https://hangsha.site/
## 기획 의도
서울대 비교과관리시스템 (https://extra.snu.ac.kr/) 에 대해 알고 계셨나요?  
교내 곳곳에서 열리는 특강, 공연, 공모전, 토론회 등 각종 비교과 행사 정보를 볼 수 있는 사이트입니다.  
그러나 정보 접근을 위해 서울대 로그인 및 인증이 필요하며, 사용성이 떨어짐에 따라 실제 서울대 학생들의 이용 정도 또한 낮다는 한계가 있습니다.

이에 따라 저희는 **행샤**를 통해, **직관적인 캘린더 UI**로 **개인화**된 행사 확인 서비스를 제공하고자 합니다!

## 핵심 기능
### 1. 캘린더
- 보다 더 직관적인 월/주/일 별 캘린더 UI
- 행사 상세 정보 조회 및 실제 신청 링크 이동
- 제목 및 다양한 조건 필터링을 통한 검색

### 2. 개인화
- 소셜 로그인 + 아이디/비밀번호 로그인
- 회원가입 온보딩 시 관심 행사 카테고리 설정
- 제외 키워드 설정 (해당 키워드 포함 행사는 캘린더에서 숨김)
- 관심 행사 북마크 및 마이페이지에서 북마크 모아보기

### 3. 시간표
- 내 시간표 생성 및 저장
- 주별 뷰에서 시간표와 겹치지 않는 행사 확인 가능
- (확장 가능성) SNUTT 연동으로 시간표 자동 불러오기

### 4. 후기
- 행사별 개인 후기(메모) 작성 가능
- 태그로 작성한 메모 분류 가능

## 팀원
| 이름 | Github ID | 분야 |
|-|-|-|
| 허서연(PM) | @h-seo-n | Frontend, UI/UX Design |
| 김하람 | @haram831 | Frontend, UI/UX Design |
| 김도향 | @D-hyang | Backend |
| 이승현 | @subir-sh | Backend, Data Scraping |
| 정혜인 | @aystoe | Backend |

### 기술 스택
- Frontend: React, TS
- Backend: Spring Boot, MySQL, Flyway, Docker, AWS S3, Nginx
- Data Scraping: OKHttp, Playwright 

## 프로젝트 뷰

### 회원가입
<img width="2366" height="1306" alt="image" src="https://github.com/user-attachments/assets/6f5419c1-3f09-4d03-9803-bd216cf9255d" />

<img width="2368" height="1266" alt="image" src="https://github.com/user-attachments/assets/2eb56478-01cc-484d-b13a-b8dd202557d2" />

<img width="2422" height="1268" alt="image" src="https://github.com/user-attachments/assets/06c9b234-397e-46b5-b1b5-ab02b2526405" />

### 로그인
<img width="2475" height="1251" alt="image" src="https://github.com/user-attachments/assets/1cbd2ad2-bafe-4e14-882e-12b7f392feb2" />

### 로그인 성공
<img width="2431" height="1227" alt="image" src="https://github.com/user-attachments/assets/a02f9508-4be9-47c4-b6a7-8eb34d7e331e" />

### 월별 뷰 페이지
<img width="2526" height="1323" alt="image" src="https://github.com/user-attachments/assets/61aa5bd0-08e8-4f25-b6ad-5820407e6855" />

### 월별 뷰 페이지 : 상세 뷰 + 필터
<img width="2527" height="1284" alt="image" src="https://github.com/user-attachments/assets/689291fa-5fb6-4e77-8038-ed56a17a1b3b" />

### 주별 뷰 페이지
<img width="2531" height="1297" alt="image" src="https://github.com/user-attachments/assets/559c0059-b59d-45c5-9330-34b02ec026f0" />

### 행사가 겹치는 경우
<img width="1479" height="1262" alt="image" src="https://github.com/user-attachments/assets/9ad29274-7e2d-46af-8a14-48990a664595" />

### 일별 뷰 페이지
<img width="2542" height="1270" alt="image" src="https://github.com/user-attachments/assets/4860da87-f70a-49c4-a28f-4ffa96f1e2ab" />

### 시간표 페이지
<img width="2526" height="1240" alt="image" src="https://github.com/user-attachments/assets/1e89a470-af7f-48db-bc71-d9c2dfebe28e" />

### 시간표 및 수업 추가
<img width="2544" height="1260" alt="image" src="https://github.com/user-attachments/assets/0591e8d6-b342-47e0-b699-990446831bbd" />

### 검색
<img width="911" height="693" alt="image" src="https://github.com/user-attachments/assets/36e4a93a-a1b7-4175-bda9-0c98f151b532" />
<img width="1173" height="692" alt="image" src="https://github.com/user-attachments/assets/3d2676d3-409b-4fb9-8efe-e04915f82e71" />

### 마이페이지
<img width="1470" height="920" alt="스크린샷 2026-02-07 14 53 56" src="https://github.com/user-attachments/assets/ae788ddd-17bb-41b6-85aa-4074830a91b9" />
<img width="1470" height="920" alt="스크린샷 2026-02-07 14 54 03" src="https://github.com/user-attachments/assets/892ba95f-1619-418f-8a96-23e27eda3f88" />



### 북마크
<img width="1169" height="696" alt="image" src="https://github.com/user-attachments/assets/d5b30f49-a03a-42b7-8277-63af5755e63e" />

### 후기 작성 : 메모, 태그
<img width="1168" height="693" alt="image" src="https://github.com/user-attachments/assets/23330d5b-0f5c-4500-9cc5-38a23e0723da" />
<img width="422" height="695" alt="image" src="https://github.com/user-attachments/assets/dcd4c47b-762d-4092-8209-0428eeef66c8" />

<img width="1170" height="691" alt="image" src="https://github.com/user-attachments/assets/4a74b3ee-0766-4c20-b3b2-99f653143d9e" />

