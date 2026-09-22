package org.liar.zhiliao.vector;

import io.milvus.param.R;

/** Milvus 响应的统一校验：null 或非 Success 一律转成 IllegalStateException。 */
final class MilvusResponses {

    static void check(R<?> response, String operation) {
        if (response == null) {
            throw new IllegalStateException("Milvus " + operation + " failed: null response");
        }
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException(
                    "Milvus " + operation + " failed: status=" + response.getStatus(),
                    response.getException());
        }
    }

    private MilvusResponses() {}
}
