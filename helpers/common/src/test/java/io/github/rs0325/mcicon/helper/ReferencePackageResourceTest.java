package io.github.rs0325.mcicon.helper;

import io.github.rs0325.mcicon.internal.ReferenceContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReferencePackageResourceTest {
    @Test void 同梱能力宣言が共通の数値契約と一致する() throws Exception {
        try(var input=getClass().getResourceAsStream("/reference-contract.json")) {
            assertNotNull(input);ReferenceContract.validate(input.readAllBytes());
        }
    }
}
