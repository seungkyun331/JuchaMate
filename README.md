# 주차메이트 (가칭) · 제출 자료

현대자동차그룹 Pleos 차량용 앱 개발 공모전 출품작의 실행 파일과 소스입니다.
이름은 가칭이며, 코드와 패키지에는 처음 이름 ParkCoach(`com.parkcoach`)가 남아 있습니다.

'주차메이트'는 차량이 보고하는 조향각·기어·속도만으로 내 차가 움직인 길을 실시간으로 계산해, 한산한 주차장의 빈 칸 둘레에 가상의 옆 차와 벽을 그리고 어디서 멈추고 핸들을 얼마나 돌리고 몇 미터 후진할지 알려 주는 Pleos Connect 주차 연습 앱입니다. 주차선과 거울은 실제이고, 부딪힐 것만 가상입니다.

## 이 폴더에 있는 것

| 경로 | 내용 |
| --- | --- |
| `apk/JuchaMate-debug.apk` | 디버그 빌드 APK (2026-11-15 빌드, 약 43 MB) |
| `source/` | 앱 전체 소스(Kotlin, Jetpack Compose)와 엔진 테스트 30개. 기능 설명은 `source/README.md` |
| `screenshots_emulator/` | Pleos Connect 에뮬레이터에서 찍은 실제 앱 화면 10장 |
| `demo_simulation/` | 시뮬레이션 영상 3개와 화면 이미지 5장 |

`demo_simulation/`의 영상과 이미지는 실제 차량이나 에뮬레이터 화면이 아니라, 앱의 화면 그리기 코드를 그대로 써서 만든 시뮬레이션입니다. 이름을 바꾸기 전에 만들어 처음 이름(파킹코치)과 이전 화면 디자인이 들어 있습니다.

`source/README.md`는 11월 15일의 화면 변경(두 갈래로 시작하는 첫 화면, 설정 화면, '내 자리'의 진입 방향 읽기) 전에 쓴 설명이라 첫 화면 설명이 지금 화면과 조금 다릅니다.

APK SHA-256: `d4618071c70b765c1b423000da4d3ada7a5accfa02c0274e777c75beda199482`

## 실행 환경

- Pleos Connect Emulator v2.1.0 (Android Automotive, Android 14 / API 34)
- Android Studio와 Android SDK Platform-Tools(`adb`)

에뮬레이터 이미지는 Android Studio의 SDK Manager → SDK Update Sites에 아래 주소를 추가해 받았습니다.

- Apple Silicon: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
- Windows / Intel Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`

AVD는 Automotive 기기에 Pleos API 34 이미지로 만들고 **Cold Boot**로 켭니다. Quick Boot로 켜면 차량 신호가 올라오지 않았습니다.

## 설치와 실행

에뮬레이터를 켠 뒤 이 폴더에서 실행합니다.

```
adb install -r apk/JuchaMate-debug.apk
adb shell appops set com.parkcoach SYSTEM_ALERT_WINDOW allow
adb forward tcp:8088 tcp:8088
```

1. 첫 줄은 앱을 설치합니다. 앱 목록에서 실행하고, 처음 실행할 때 차량 신호 권한을 허용합니다.
2. 둘째 줄은 후진 중 후방 카메라 위에 작은 안내 창을 띄우는 데 필요한 '다른 앱 위에 표시' 권한입니다(선택).
3. 셋째 줄은 Pleos Virtual Console을 `http://127.0.0.1:8088`에 연결합니다. 에뮬레이터를 켤 때마다 다시 실행해야 하고, 콘솔 탭은 화면에 보이는 상태로 둡니다.

## 시연 순서 (Virtual Console + 앱)

1. 콘솔은 기어 P, 속도 0으로 둡니다. 앱 첫 화면의 '가상 주차장에서 연습'에서 `이어서 연습` → `여기를 주차 칸으로`를 누릅니다. 지금 차가 선 자리가 목표 칸이 되고 둘레에 가상의 차가 그려집니다.
2. 콘솔에서 기어를 D에 두고 핸들을 돌린 뒤 천천히 전진합니다. 앱 화면의 차가 칸에서 나가고, 화면의 핸들이 콘솔의 조향각대로 돕니다.
3. 멈추고 기어를 R에 둡니다. Pleos Connect가 후방 카메라를 띄우고, 그 위 구석에 앱의 안내 창이 뜹니다.
4. 같은 핸들로 후진해 칸에 넣고, 멈춘 뒤 기어를 P에 둡니다. 앱이 주차를 채점해 결과를 보여 줍니다.

콘솔의 ACCEL 버튼은 페달 100%라서 주차 속도를 맞추기 어렵습니다. 같은 콘솔에 HTTP로 보내면 속도를 정수 km/h로 정할 수 있습니다.

```
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=3&accelerator=5&brake=0&steering=450"
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=100&steering=450"
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Reverse"
curl "http://127.0.0.1:8088/vehicle/state"
```

에뮬레이터에서 조향각은 핸들 각도 단위(±500)이고 왼쪽이 양수였습니다.

## 확인한 범위

| 항목 | 상태 |
| --- | --- |
| Android Studio 빌드, Pleos Connect 에뮬레이터(v2.1.0) 실행 | 확인 |
| 에뮬레이터의 조향각·기어·속도·문 신호 읽기 | 확인 |
| 핸들을 꺾어 칸에서 나갔다가 후진으로 다시 넣고, 기어 P에서 채점 결과 | 확인 (11월 15일) |
| 후진 중 후방 카메라 위 안내 창 | 확인 (에뮬레이터) |
| 안내가 말한 수치를 그대로 따라가 별을 받는 주차 | 아직 확인하지 못함 |
| 음성 출력 | 아직 확인하지 못함 |
| 실제 차량 | 아직 확인하지 못함 |

주차 엔진(궤적 계산, 경로 계획, 판정)은 차량 없이 시뮬레이션으로 검증했습니다. `source/`에서 아래 명령으로 다시 돌릴 수 있습니다.

```
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleDebug -PpleosSdk=false
```

첫 줄은 엔진 테스트 30개, 둘째 줄은 APK 빌드(`app/build/outputs/apk/debug/app-debug.apk`), 셋째 줄은 Pleos 저장소에 닿지 않을 때의 빌드입니다.

## 안전과 한계

- 앱은 차량을 제어하지 않습니다. 신호를 읽기만 합니다.
- 화면의 차와 벽은 가상이며, 앱은 실제 장애물과 사람을 보지 못합니다. 주변은 운전자가 직접 확인해야 합니다.
- 읽지 못한 신호는 0으로 치지 않고 안내를 멈추며, 시속 15 km를 넘으면 안내를 멈춥니다.

## 차량 3D 모델 출처 (CC BY 4.0)

내 차는 모델 그대로, 주변에 주차된 차는 무광 회색으로 바꿔 그렸습니다. 모두 CC BY 4.0입니다.

- This work is based on "Hyundai Ioniq 5 - Lowpoly" (https://sketchfab.com/3d-models/hyundai-ioniq-5-lowpoly-675e78311e8440d88714bd212cb7a8fb) by andikapratamaw (https://sketchfab.com/andikapratamaw) licensed under CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/)
- This work is based on "2023 Hyundai Casper" (https://sketchfab.com/3d-models/2023-hyundai-casper-756bda485fc840babe274997874dde1a) by tonielpro520 (https://sketchfab.com/tonielpro520) licensed under CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/)
- This work is based on "2022 Hyundai Staria" (https://sketchfab.com/3d-models/2022-hyundai-staria-aa616c471fba41ccbaa913c415853657) by tonielpro520 (https://sketchfab.com/tonielpro520) licensed under CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/)
- This work is based on "Hyundai Elantra 2022 [ Ultra Low Poly ]" (https://sketchfab.com/3d-models/hyundai-elantra-2022-ultra-low-poly-b30d0baf6c3d4ca4adf2f484a90fc861) by AhmedE (https://sketchfab.com/erkenselami92) licensed under CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/)

## 개인정보

이 폴더에는 이름, 연락처, 계정 정보를 넣지 않았습니다.
