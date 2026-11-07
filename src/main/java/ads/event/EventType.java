package ads.event;

/**
 * 측정 경로에서 받는 이벤트 종류.
 * - RENDERED: 광고가 DOM에 그려짐(IAB 노출 정의: 렌더링 시작 시점)
 * - VIEWABLE: 광고 50% 이상이 1초 연속 화면에 보임(IAB/MRC 뷰어빌리티)
 * - CLICK: 사용자가 광고를 누름. CPC 과금의 원천
 */
public enum EventType { RENDERED, VIEWABLE, CLICK }
