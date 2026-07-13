package io.wedocs.doc.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// 컨트롤러 파라미터에 인증 사용자의 UUID를 주입한다 — JWT `sub`→UUID 변환은
/// CurrentUserIdArgumentResolver 경계 1회(design-patterns P6), 컨트롤러엔 raw 클레임 미노출.
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
