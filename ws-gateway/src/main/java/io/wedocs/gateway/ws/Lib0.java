package io.wedocs.gateway.ws;

import java.io.ByteArrayOutputStream;

/// lib0 가변길이 인코딩(Yjs/y-protocols 와이어 포맷)의 최소 구현.
/// sync 프로토콜은 부호 없는 varUint와 varUint8Array(varBuffer)만 사용 — 부호 있는 varInt는 불필요.
/// 출처: yjs/lib0 encoding.js/decoding.js (v1, Yjs 호환).
final class Lib0 {

    private static final int CONTINUATION_BIT = 0x80;
    private static final int LOW_7_BITS = 0x7F;

    private Lib0() {
    }

    /// unsigned LEB128: 하위 7비트씩 little-endian, 뒤 바이트가 남으면 MSB(0x80) 세팅.
    static void writeVarUint(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("varUint는 음수를 표현하지 않는다: " + value);
        }
        long remaining = value;
        while (remaining > LOW_7_BITS) {
            out.write((int) (CONTINUATION_BIT | (remaining & LOW_7_BITS)));
            remaining >>>= 7;
        }
        out.write((int) (remaining & LOW_7_BITS));
    }

    /// varBuffer = varUint(length) • bytes.
    static void writeVarUint8Array(ByteArrayOutputStream out, byte[] payload) {
        writeVarUint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    /// 한 와이어 메시지를 순차 파싱하는 byte[] 커서 디코더.
    static final class Decoder {

        private final byte[] buf;
        private int pos;

        Decoder(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

        boolean hasMore() {
            return pos < buf.length;
        }

        long readVarUint() {
            long result = 0;
            int shift = 0;
            while (pos < buf.length) {
                // shift=63 시점은 10번째 바이트의 첫 비트가 long 부호 비트(63)에 닿아 양수 보장 불가
                // (상위 비트는 64를 넘어 소실) → 음수/손상값 반환 전에 차단(손상·악의 프레임).
                if (shift >= 63) {
                    throw new IllegalArgumentException("varUint 오버플로(>63비트)");
                }
                int b = buf[pos++] & 0xFF;
                result |= (long) (b & LOW_7_BITS) << shift;
                if ((b & CONTINUATION_BIT) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("varUint 디코드 중 입력이 조기 종료됨");
        }

        byte[] readVarUint8Array() {
            long len = readVarUint();
            int remaining = buf.length - pos;
            if (len > remaining) {
                throw new IllegalArgumentException("varBuffer 길이가 남은 바이트를 초과: len=" + len + " remaining=" + remaining);
            }
            byte[] payload = new byte[(int) len];
            System.arraycopy(buf, pos, payload, 0, (int) len);
            pos += (int) len;
            return payload;
        }
    }
}
