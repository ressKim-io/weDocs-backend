package io.wedocs.gateway.ws;

/// 통합 테스트가 공유하는 백엔드 대역(엔진·doc-service). **JVM당 한 번만** 기동한다.
///
/// 테스트 클래스마다 기동/종료하면 Spring 컨텍스트 캐시 키가 갈려(포트가 클래스마다 달라진다) 컨텍스트가
/// 매번 새로 뜬다. 포트를 고정해 두면 모든 통합 테스트가 같은 프로퍼티 집합을 보게 되어 컨텍스트 하나를
/// 재사용한다. 정리는 JVM 종료 훅에 맡긴다 — 어느 테스트 클래스가 마지막인지 알 필요가 없다.
final class WsBackends {

    static final FakeCrdtEngine ENGINE = new FakeCrdtEngine();
    static final FakeDocService DOC_SERVICE = new FakeDocService();

    static final int ENGINE_PORT;
    static final int DOC_SERVICE_PORT;

    static {
        ENGINE_PORT = ENGINE.startOnRandomPort();
        DOC_SERVICE_PORT = DOC_SERVICE.startOnRandomPort();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ENGINE.stop();
            DOC_SERVICE.stop();
        }, "ws-backends-shutdown"));
    }

    private WsBackends() {
    }
}
