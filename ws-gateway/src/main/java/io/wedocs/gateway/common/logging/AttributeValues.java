package io.wedocs.gateway.common.logging;

import java.util.List;

/// 속성 값 정규화 — OTLP가 기본 지원하는 타입 집합(문자열·불리언·정수·부동소수 및 동종 배열)만
/// 그대로 통과시키고, 밖의 값은 단일 라인 문자열로 접는다.
///
/// 타입 제한 근거: OTLP LogRecord 속성은 string·bool·int64·double 및 그 동종 배열만 허용한다.
/// 이 집합 밖의 값을 그대로 보내면 내보내기 시점에 손실 변환이 생기거나 수집기가 거부한다.
/// 지금 허용 타입으로 강제하면 파일 기반 JSON에서도 정수·불리언이 타입 보존되고,
/// OTLP 전환 시 속성 손실 없이 무손실 승격된다.
///
/// 변환 규칙:
/// - null → null (emitter가 해당 속성을 생략한다)
/// - 허용 타입 → 그대로 반환
/// - 그 외 → `toString()` 호출 → 개행(`\n`, `\r`)·탭(`\t`)을 공백 1개로 접음 → 1024자 절단
/// - `toString()`이 예외를 던지면 → `<unrenderable:SimpleClassName>`
public final class AttributeValues {

    private static final int MAX_STRING_LENGTH = 1024;

    private AttributeValues() {
    }

    /// 값을 OTLP 호환 타입으로 정규화한다.
    /// null이면 null을 반환하고, emitter는 해당 속성을 생략해야 한다.
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (isAllowedType(value)) {
            return value;
        }
        return toSingleLineString(value);
    }

    private static boolean isAllowedType(Object value) {
        // 스칼라 허용 타입
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double) {
            return true;
        }

        // 프리미티브 배열
        if (value instanceof String[]
                || value instanceof boolean[]
                || value instanceof int[]
                || value instanceof long[]
                || value instanceof float[]
                || value instanceof double[]) {
            return true;
        }

        // 박싱 배열
        if (value instanceof Boolean[]
                || value instanceof Integer[]
                || value instanceof Long[]
                || value instanceof Float[]
                || value instanceof Double[]) {
            return true;
        }

        // 동종 리스트
        if (value instanceof List<?> list) {
            return isHomogeneousAllowedList(list);
        }

        return false;
    }

    private static boolean isHomogeneousAllowedList(List<?> list) {
        if (list.isEmpty()) {
            return true;
        }

        Object first = list.getFirst();
        if (first == null) {
            return false;
        }

        Class<?> elementType = first.getClass();
        if (!isAllowedScalarType(elementType)) {
            return false;
        }

        for (int i = 1; i < list.size(); i++) {
            Object element = list.get(i);
            if (element == null || element.getClass() != elementType) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedScalarType(Class<?> type) {
        return type == String.class
                || type == Boolean.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class;
    }

    private static String toSingleLineString(Object value) {
        String text;
        try {
            text = value.toString();
        } catch (Exception e) {
            return "<unrenderable:" + value.getClass().getSimpleName() + ">";
        }

        if (text == null) {
            return "<unrenderable:" + value.getClass().getSimpleName() + ">";
        }

        // 개행·탭을 공백으로 접는다
        text = collapseWhitespace(text);

        // 1024자 절단
        if (text.length() > MAX_STRING_LENGTH) {
            text = text.substring(0, MAX_STRING_LENGTH);
        }

        return text;
    }

    private static String collapseWhitespace(String text) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                    sb.append(text, 0, i);
                }
                sb.append(' ');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }
}
