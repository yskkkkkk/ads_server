package ads.event;

import java.util.regex.Pattern;

/**
 * GIVT(일반 무효 트래픽) 거르기: User-Agent가 알려진 봇, 크롤러 모양이면 이벤트로 받지 않습니다.
 *
 * 지키는 규칙
 * - User-Agent가 비었거나 bot, crawler, spider, headless가 들어 있으면 무효입니다.
 * - 무효 이벤트는 스트림에 넣지 않고(과금, 성과 집계에서 빠짐), 걸러진 건수만 따로 셉니다.
 *
 * 왜 이 방식인지
 * - GIVT는 "목록으로 거를 수 있는 수준"의 무효 트래픽입니다(CLAUDE.md 3-8). 실제로는 IAB의 봇 목록을 쓰지만
 *   여기서는 단어 몇 개로 흉내 냅니다. 분석이 필요한 SIVT(클릭 농장 등)는 4단계 10초 규칙이 일부를 맡습니다.
 * - 걸러진 건수를 남기는 이유: 광고주가 "우리 광고 클릭이 왜 이것뿐이냐"고 물을 때, 그리고 5단계 대사에서
 *   "수집 단계에서 빠진 것"과 "과금 단계에서 빠진 것"을 나눠 보여주기 위해서입니다.
 * - curl은 목록에 넣지 않았습니다. 사용자가 curl로 직접 시나리오를 돌려 보기 때문입니다. 봇 흉내는 curl -A "Googlebot" 으로 합니다.
 *
 * 이 규칙이 깨지면
 * - 크롤러가 광고 링크를 따라가며 남긴 클릭이 그대로 과금되어 광고주가 사람이 아닌 트래픽에 돈을 냅니다.
 */
public final class BotFilter {

    private static final Pattern BOT = Pattern.compile("(?i)bot|crawler|spider|headless");

    private BotFilter() {
    }

    public static boolean isGivt(String userAgent) {
        return userAgent == null || userAgent.isBlank() || BOT.matcher(userAgent).find();
    }
}
