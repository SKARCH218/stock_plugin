# Minecraft 주식 플러그인

이 플러그인은 마인크래프트 서버 내에 주식 시장을 시뮬레이션하여 플레이어가 가상 주식을 사고팔고 거래할 수 있도록 합니다. 경제 시스템을 위해 Vault와 연동되며, 주식 가격 표시를 위해 PlaceholderAPI와 통합됩니다.

## 기능

-   **동적인 주식 가격:** 주식 가격은 추세 기반 알고리즘에 따라 변동합니다.
-   **GUI 기반 거래:** 플레이어는 직관적인 그래픽 사용자 인터페이스를 통해 주식을 사고팔 수 있습니다.
-   **GUI 개선 및 뒤로가기:** 모든 GUI 페이지에 뒤로가기 버튼이 추가되어 편리한 탐색이 가능합니다.
-   **플레이어 포트폴리오:** 플레이어는 자신이 소유한 주식, 평균 매수 가격, 현재 가치, 그리고 손익을 한눈에 볼 수 있습니다.
-   **투자 순위표:** 서버 내 최고 투자자들을 보여주는 리더보드를 제공합니다.
-   **거래 수수료:** 주식 매수 및 매도에 대한 수수료를 설정할 수 있습니다.
-   **주식 소식 구독:** 특정 주식의 가격 변동 알림을 구독하고 해지할 수 있습니다. 주가 변동 시 구독자에게 알림 메시지가 전송됩니다.
-   **일일 거래 제한:** 플레이어당 하루 주식 구매 및 판매 횟수를 제한할 수 있습니다.
-   **관리자 명령어:** 설정 파일을 리로드하고 주식 가격을 수동으로 설정하는 명령어를 제공합니다.
-   **PlaceholderAPI 지원:** PlaceholderAPI 자리표시자를 사용하여 실시간 주식 가격을 표시할 수 있습니다.
-   **커스터마이징 가능한 GUI 버튼:** GUI 버튼의 재료, 커스텀 모델 데이터, 이름, 로어를 설정할 수 있습니다.
-   **다국어 지원:** 모든 플러그인 메시지는 `lang.yml`을 통해 사용자 정의할 수 있습니다.

## 설치

1.  [릴리스 페이지](LINK_TO_RELEASES_PAGE)에서 최신 버전의 플러그인을 다운로드합니다.
2.  `StockPlugin.jar` 파일을 서버의 `plugins` 폴더에 넣습니다.
3.  서버에 **Vault**와 **PlaceholderAPI**가 설치되어 있는지 확인합니다.
4.  서버를 재시작합니다.

## 명령어

-   `/주식` (또는 `/stock`, `/stocks`): 메인 주식 시장 GUI를 엽니다.
-   **탭 자동 완성 지원:** 모든 `/주식` 명령어에 대해 탭 자동 완성 기능이 제공됩니다.

### 관리자 명령어

-   `/주식 관리 reload`: 플러그인의 설정 파일(`config.yml`, `stock.yml`, `lang.yml`)을 리로드합니다.
-   `/주식 관리 setprice <stock_id> <price>`: 특정 주식의 가격을 설정합니다.

### 구독 명령어

-   `/주식 구독 추가 <stock_id>`: 특정 주식의 가격 변동 알림을 구독합니다.
-   `/주식 구독 제거 <stock_id>`: 특정 주식의 가격 변동 알림 구독을 해지합니다.
-   `/주식 구독 목록`: 현재 구독 중인 주식 목록을 확인합니다.

## 설정

### `config.yml`

```yaml
update-interval-seconds: 60

# 거래 수수료 (단위: %)
# 0.5는 0.5%를 의미합니다. 0으로 설정하면 수수료가 없습니다.
# 매수 시에는 (가격 * 수량) * (1 + 수수료율) 만큼 차감됩니다.
# 매도 시에는 (가격 * 수량) * (1 - 수수료율) 만큼 지급됩니다.
transaction-fee-percent: 0.5

# 주식 가격 변동 알림 임계값 (단위: %)
# 주식 가격이 이 값 이상 변동하면 구독자에게 알림이 전송됩니다.
# 예를 들어 1.0으로 설정하면 1% 이상 가격이 변동될 때 알림이 갑니다.
notification-threshold-percent: 1.0

# 하루 1인당 주식 거래 제한 기능
stock-transaction-limits:
  enable: true # 사용 여부
  # 0으로 설정시 무한
  buy: 10 # 하루에 10번 구매가능
  sell: 10 # 하루에 10번 판매가능

gui:
  main-title: "§l주식 시장"
  portfolio-title: "§l내 주식 현황"
  ranking-title: "§l투자 순위표"
  # 이 값이 true이면, 모든 GUI 아이템이 STRUCTURE_VOID로 대체됩니다.
  # 투명한 STRUCTURE_VOID 텍스처를 가진 커스텀 리소스 팩에 유용합니다.
  items-all-structure-void: false
  buttons:
    portfolio:
      material: PLAYER_HEAD
      custom-model-data: 0
      name: "§a내 주식 현황"
      lore:
        - "§7클릭하여 내 주식 정보를 봅니다."
    ranking:
      material: EMERALD
      custom-model-data: 0
      name: "§b투자 순위표"
      lore:
        - "§7클릭하여 투자 순위를 봅니다."
```

### `stock.yml`

```yaml
stocks:
  apple:
    name: "사과"
    initial-price: 1000.0
    fluctuation: 100.0
  samsung:
    name: "삼성"
    initial-price: 5000.0
    fluctuation: 500.0
```

### `lang.yml`

```yaml
# 모든 플러그인 메시지는 여기에 정의됩니다.
# 원하는 대로 사용자 정의할 수 있습니다.
plugin-enabled: "§aStock Plugin Enabled."
plugin-disabled: "§cStock Plugin Disabled."
permission-denied: "§c권한이 없습니다."
player-only-command: "§c플레이어만 사용할 수 있는 명령어입니다."

admin-command-usage: "§c사용법: /주식 관리 <리로드|가격설정>"
admin-reload-success: "§a설정을 리로드했습니다."
admin-setprice-usage: "§c사용법: /주식 관리 가격설정 <주식ID> <가격>"
admin-invalid-stock-or-price: "§c잘못된 주식 ID 또는 가격입니다."
admin-setprice-success: "§a%stock_name%의 가격을 %price%로 설정했습니다."
admin-unknown-command: "§c알 수 없는 관리 명령어입니다."

stock-loading-info: "§7주식 정보를 불러오는 중..."
stock-portfolio-loading-info: "§7주식 정보를 불러오는 중..."
stock-ranking-loading-info: "§7랭킹 정보를 불러오는 중..."

buy-gui-title: "§a%stock_name% 구매"
sell-gui-title: "§c%stock_name% 판매"

not-enough-money: "§c돈이 부족합니다. (수수료 포함: %cost%원)"
not-enough-stock: "§c보유 주식이 부족합니다."
buy-success: "§a%stock_name% %amount%주를 매수했습니다."
sell-success: "§a%stock_name% %amount%주를 매도했습니다."

portfolio-total-asset: "§6%total_asset%원"
portfolio-stock-quantity: "§6%amount%주"
portfolio-avg-price: "§6%avg_price%원"
portfolio-current-price: "§6%current_price%원"
portfolio-current-value: "§6%current_value%원"
portfolio-profit-loss: "§6%profit_loss%원 (%profit_loss_percent%%)"

ranking-total-asset: "§6%total_asset%원"

gui-back-button-name: "§c뒤로가기"
gui-back-button-lore: "§7이전 화면으로 돌아갑니다."

subscribe-command-usage: "§c사용법: /주식 구독 <추가|제거|목록> [주식ID]"
subscribe-add-usage: "§c사용법: /주식 구독 추가 <주식ID>"
subscribe-remove-usage: "§c사용법: /주식 구독 제거 <주식ID>"
subscribe-list-empty: "§e구독 중인 주식이 없습니다."
subscribe-list-header: "§e--- 구독 중인 주식 ---"
subscribe-add-success: "§a%stock_name% 주식 알림을 구독했습니다."
subscribe-remove-success: "§a%stock_name% 주식 알림을 구독 해지했습니다."
invalid-stock-id: "§c유효하지 않은 주식 ID입니다."
subscribe-unknown-command: "§c알 수 없는 구독 명령어입니다."

stock-price-increase-notification: "§a[주식 알림] %stock_name%의 가격이 상승했습니다! (§f%old_price%원 -> %new_price%원, +%change_percent%%)"
stock-price-decrease-notification: "§c[주식 알림] %stock_name%의 가격이 하락했습니다! (§f%old_price%원 -> %new_price%원, %change_percent%%)"

transaction-limit-exceeded-buy: "§c오늘 이 주식의 구매 한도를 초과했습니다. (일일 한도: %limit%회)"
transaction-limit-exceeded-sell: "§c오늘 이 주식의 판매 한도를 초과했습니다. (일일 한도: %limit%회)"
```

## PlaceholderAPI 자리표시자

-   `%stock_price_<stock_id>%`: 지정된 주식의 현재 가격을 표시합니다. `<stock_id>`를 실제 주식 ID(예: `apple`, `samsung`)로 대체하세요.

## 개발

이 플러그인은 Kotlin과 Gradle을 사용하여 개발되었습니다.

### 소스에서 빌드하기

1.  저장소를 클론합니다.
2.  `./gradlew build` (Windows에서는 `gradlew.bat build`) 명령어를 실행합니다.
3.  컴파일된 `.jar` 파일은 `build/libs` 디렉토리에 있습니다.

## 라이선스

이 프로젝트는 MIT 라이선스에 따라 라이선스가 부여됩니다. 자세한 내용은 LICENSE 파일을 참조하십시오.