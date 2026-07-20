package io.wedocs.gateway.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/// gRPC 클라이언트 설정 바인딩. 프로퍼티 타입을 쓰는 패키지가 직접 활성화해, 설정 소유가 흩어지지 않게 한다.
@Configuration
@EnableConfigurationProperties(DocServiceProperties.class)
public class GrpcConfig {
}
