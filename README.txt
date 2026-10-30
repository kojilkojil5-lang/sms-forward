문자 자동전달 앱 - 빌드 방법

1. 안드로이드 스튜디오 설치 (developer.android.com/studio, 무료)
2. 이 압축을 풀고, 안드로이드 스튜디오 > Open > SmsForward 폴더 선택
   (처음 열 때 Gradle·SDK 자동 다운로드로 수 분 걸림. 업그레이드 안내가 뜨면 그대로 진행)
3. 메뉴 Build > Build App Bundle(s) / APK(s) > Build APK(s)
4. 생성 위치: app/build/outputs/apk/debug/app-debug.apk
5. 이 파일을 폰으로 옮겨 설치 (출처를 알 수 없는 앱 설치 허용 필요)
   - 또는 폰을 USB로 연결하고 개발자 옵션의 USB 디버깅을 켠 뒤 상단 ▶ 실행 버튼

폰에서 설정
- 앱 실행 > 문자 권한 허용 > 받을 번호 입력 > 자동전달 켜기 > 설정 저장
- 배터리 최적화 예외 등록 버튼도 눌러주는 것을 권장
- 테스트 문자 보내기로 연결 확인
