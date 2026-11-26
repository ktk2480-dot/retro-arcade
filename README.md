# 레트로 아케이드 (Retro Arcade)

폰을 휴대용 아케이드 게임기처럼 쓰는 안드로이드 앱입니다. 직접 보유한 아케이드 ROM(zip)을 폴더에 넣으면 목록에서 골라 바로 실행합니다.

- **세로**: 위에 게임 화면, 아래 왼쪽에 조이스틱, 아래 오른쪽에 버튼, 그 위에 COIN / START
- **가로**: 폰을 돌리면 게임을 다시 시작하지 않고 가운데 게임 화면, 왼쪽 조이스틱, 오른쪽 버튼으로 바뀝니다
- 에뮬레이터 코어 2종 내장: **FBNeo**, **MAME 2003-Plus**
- 상태 저장/불러오기(슬롯 3개), CRT 스캔라인 필터, 4버튼/6버튼 전환, 회전 고정

요구 사항: Android 8.0 이상, 64비트(arm64) 기기.

## 설치

1. 이 저장소의 **Releases**에서 최신 `RetroArcade-x.y.z.apk`를 폰으로 내려받습니다.
2. 파일을 열어 설치합니다. (처음에는 "출처를 알 수 없는 앱 설치"를 허용해야 합니다.)
3. 앱을 열고 **저장소 접근 허용**을 누른 뒤 "모든 파일에 대한 접근"을 켭니다.

## ROM 넣기

- ROM zip은 **압축을 풀지 않고** 한 폴더에 둡니다. 기본 폴더는 `Download`이며, 앱의 **폴더** 버튼으로 바꿀 수 있습니다.
- 네오지오 등 BIOS가 필요한 게임은 BIOS zip(예: `neogeo.zip`)을 같은 폴더에 둡니다.
- 클론 세트는 부모 ROM zip도 같은 폴더에 있어야 할 수 있습니다.
- ROM과 BIOS는 앱에 들어 있지 않으며, 앱이 내려받지도 않습니다. 직접 보유한 것만 사용하세요.

### 목록의 색 표시

앱은 각 zip 안의 파일을 두 코어의 게임 목록과 대조해 맞는 코어를 고릅니다.

| 표시 | 뜻 |
|---|---|
| 초록 | 필요한 파일이 모두 있어 실행 가능 |
| 노랑 | 시도 가능 (BIOS zip 없음, 에뮬레이션 미완성 게임, 7z 등) |
| 빨강 | 롬셋 불일치 또는 부모 ROM 없음 |

아케이드 ROM은 **코어 버전에 맞는 세트**여야 실행됩니다. FBNeo는 최신 FBNeo 세트, MAME 2003-Plus는 MAME 0.78 계열 세트를 씁니다. 게임을 길게 누르면 코어를 직접 고를 수 있습니다.

## 조작

| 화면 | 동작 |
|---|---|
| 조이스틱 | 8방향 |
| A B (C) / C D 또는 X Y Z | 게임 버튼. 4버튼: 아래 A B, 위 C D. 6버튼: 위 X Y Z, 아래 A B C |
| COIN / START | 코인 투입 / 시작 |
| MENU 또는 뒤로가기 | 상태 저장·불러오기, CRT 필터, 버튼 배열, 화면 회전, 리셋, 종료 |

상태 저장은 FBNeo에서 안정적으로 동작하고, MAME 2003-Plus는 게임에 따라 지원하지 않습니다.

## 빌드

`main` 브랜치에 푸시하면 GitHub Actions(`.github/workflows/build.yml`)가 APK를 빌드해 Releases에 올립니다.

- 에뮬레이터 코어는 저장소에 넣지 않고, 빌드할 때 libretro 공식 빌드봇에서 arm64용을 내려받아 APK에 넣습니다.
- 서명 키는 저장소 Secrets(`KEYSTORE_B64`, `KEYSTORE_PASSWORD`)에 있습니다. 같은 키로 서명해야 기존 설치 위에 업데이트됩니다. Secrets가 없으면 임시 디버그 키로 서명되어 덮어쓰기 설치가 되지 않습니다.
- `app/src/main/assets/gamedb.bin`은 `tools/build_gamedb.py`로 두 코어의 게임 목록에서 만든 것입니다.

PC에서 직접 빌드하려면 두 코어의 `.so`를 `app/src/main/jniLibs/arm64-v8a/`에 `lib<코어>_libretro_android.so` 이름으로 넣고 Android Studio에서 엽니다.

## 라이선스와 고지

- 이 저장소의 소스: GPL-3.0 (`LICENSE`). 에뮬레이터 프런트엔드 라이브러리 [LibretroDroid](https://github.com/Swordfish90/LibretroDroid)(GPL-3.0)를 사용합니다.
- [FBNeo](https://github.com/libretro/FBNeo)와 [MAME 2003-Plus](https://github.com/libretro/mame2003-plus-libretro) 코어는 각자의 **비상업** 라이선스를 따릅니다. 이 APK를 판매하거나 유료 서비스에 포함할 수 없습니다.
- APK를 다른 사람에게 줄 때는 이 소스도 함께 받을 수 있게 해야 합니다(GPL).
- 게임 ROM과 BIOS의 저작권은 각 권리자에게 있습니다.
