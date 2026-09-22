package org.liar.zhiliao.vector;

/** zhiliao_chunks 集合字段名的唯一来源：建表与读写共用同一组常量，避免字面量漂移。 */
final class MilvusSchema {

    static final String ID_FIELD = "id";
    static final String TEXT_FIELD = "text";
    static final String METADATA_FIELD = "metadata";
    static final String VECTOR_FIELD = "vector";
    static final String KB_ID_FIELD = "kb_id";

    private MilvusSchema() {}
}
