package io.wedocs.doc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/// doc-service 부트스트랩. 문서 메타·권한·스냅샷 영속화 (M2).
/// I/O 바운드(DB) → Virtual Thread(application.yml).
@SpringBootApplication
public class DocServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocServiceApplication.class, args);
    }
}
