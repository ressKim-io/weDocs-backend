package io.wedocs.gateway.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// lib0 가변길이 인코딩 단위 테스트. 출처 와이어 포맷(yjs/lib0 encoding.js/decoding.js)과
/// 정확히 호환되는지(known-byte 벡터) + 라운드트립을 검증한다.
class Lib0Test {

    private static byte[] encodeVarUint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, value);
        return out.toByteArray();
    }

    @Test
    @DisplayName("writeVarUint은 lib0 known-byte 벡터와 일치한다")
    void writeVarUint_matchesKnownVectors() {
        // Given/When/Then: lib0 unsigned LEB128 (하위 7비트 우선, MSB=연속)
        assertThat(encodeVarUint(0)).containsExactly(0x00);
        assertThat(encodeVarUint(1)).containsExactly(0x01);
        assertThat(encodeVarUint(127)).containsExactly(0x7F);
        assertThat(encodeVarUint(128)).containsExactly(0x80, 0x01);
        assertThat(encodeVarUint(16383)).containsExactly(0xFF, 0x7F);
        assertThat(encodeVarUint(16384)).containsExactly(0x80, 0x80, 0x01);
    }

    @Test
    @DisplayName("readVarUint은 known-byte 벡터를 정확히 디코드한다")
    void readVarUint_decodesKnownVectors() {
        // Given: [0x80, 0x01] = 128
        Lib0.Decoder decoder = new Lib0.Decoder(new byte[]{(byte) 0x80, (byte) 0x01});

        // When/Then
        assertThat(decoder.readVarUint()).isEqualTo(128L);
        assertThat(decoder.hasMore()).isFalse();
    }

    @Test
    @DisplayName("varUint 라운드트립: 경계값과 큰 값을 보존한다")
    void varUint_roundTrip_preservesValues() {
        long[] values = {0, 1, 2, 127, 128, 255, 16383, 16384, 0x7FFFFFFFL, 1L << 40};

        for (long value : values) {
            // Given/When
            Lib0.Decoder decoder = new Lib0.Decoder(encodeVarUint(value));

            // Then
            assertThat(decoder.readVarUint()).as("value=%d", value).isEqualTo(value);
        }
    }

    @Test
    @DisplayName("varUint8Array(varBuffer) 라운드트립: 빈/소/대 페이로드를 보존한다")
    void varUint8Array_roundTrip_preservesPayload() {
        byte[][] payloads = {
                new byte[0],
                new byte[]{1, 2, 3},
                "hello-state-vector".getBytes()
        };

        for (byte[] payload : payloads) {
            // Given
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Lib0.writeVarUint8Array(out, payload);

            // When
            Lib0.Decoder decoder = new Lib0.Decoder(out.toByteArray());

            // Then
            assertThat(decoder.readVarUint8Array()).isEqualTo(payload);
            assertThat(decoder.hasMore()).isFalse();
        }
    }

    @Test
    @DisplayName("두 varBuffer를 연속으로 쓰면 순서대로 다시 읽힌다 (와이어 스트림 파싱)")
    void multiplePayloads_decodeSequentially() {
        // Given: 와이어 메시지처럼 여러 필드가 한 버퍼에 연속
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, 0);              // messageSync
        Lib0.writeVarUint(out, 2);              // Update
        Lib0.writeVarUint8Array(out, new byte[]{10, 20, 30});

        // When
        Lib0.Decoder decoder = new Lib0.Decoder(out.toByteArray());

        // Then
        assertThat(decoder.readVarUint()).isEqualTo(0L);
        assertThat(decoder.readVarUint()).isEqualTo(2L);
        assertThat(decoder.readVarUint8Array()).containsExactly(10, 20, 30);
        assertThat(decoder.hasMore()).isFalse();
    }

    @Test
    @DisplayName("음수 varUint 쓰기는 거부한다")
    void writeVarUint_rejectsNegative() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> Lib0.writeVarUint(out, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("varUint 입력이 조기 종료되면(연속 비트만 있고 끝) 거부한다")
    void readVarUint_rejectsTruncatedInput() {
        // Given: 0x80(연속) 뒤에 바이트 없음
        Lib0.Decoder decoder = new Lib0.Decoder(new byte[]{(byte) 0x80});

        // When/Then
        assertThatThrownBy(decoder::readVarUint)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("varUint이 63비트를 넘으면(10번째 바이트) 음수 반환 대신 거부한다")
    void readVarUint_rejectsOverflowBeyond63Bits() {
        // Given: 9 × 0x80(연속) + 종료 바이트 → shift가 63에 도달해 long 부호 비트 침범 직전 차단
        byte[] tenBytes = new byte[10];
        Arrays.fill(tenBytes, 0, 9, (byte) 0x80);
        tenBytes[9] = 0x00;
        Lib0.Decoder decoder = new Lib0.Decoder(tenBytes);

        // When/Then
        assertThatThrownBy(decoder::readVarUint)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("varBuffer 길이가 남은 바이트를 초과하면 거부한다 (손상 프레임)")
    void readVarUint8Array_rejectsLengthOverflow() {
        // Given: len=5 인데 페이로드 2바이트뿐
        Lib0.Decoder decoder = new Lib0.Decoder(new byte[]{0x05, 1, 2});

        // When/Then
        assertThatThrownBy(decoder::readVarUint8Array)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
