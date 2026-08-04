package io.wedocs.doc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jqwik 엔진 등록·실행 스모크 테스트.
 * 이 테스트가 실행되면 JUnit Platform 위에서 jqwik 엔진이 정상 등록된 것이다.
 */
class JqwikSmokeTest {

    @Property(tries = 10)
    void stringsAreNeverNull(@ForAll String s) {
        assertThat(s).isNotNull();
    }
}
