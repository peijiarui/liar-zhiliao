package org.liar.zhiliao.vector;

import io.milvus.grpc.DataType;
import io.milvus.param.collection.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MilvusCollectionManagerTest {

    private static final int DIM = 1024;
    private static final String COLLECTION = "zhiliao_chunks";

    private static FieldType kbField(boolean partitionKey) {
        return FieldType.newBuilder()
                .withName("kb_id")
                .withDataType(DataType.Int64)
                .withPartitionKey(partitionKey)
                .build();
    }

    private static FieldType vectorField(int dimension) {
        return FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();
    }

    @Test
    void validateSchemaAcceptsExpectedSchema() {
        assertDoesNotThrow(() -> MilvusCollectionManager.validateSchema(
                List.of(kbField(true), vectorField(DIM)), DIM, COLLECTION));
    }

    @Test
    void validateSchemaRejectsMissingKbIdWithDropHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(vectorField(DIM)), DIM, COLLECTION));

        assertTrue(ex.getMessage().contains("drop"));
        assertTrue(ex.getMessage().contains(COLLECTION));
    }

    @Test
    void validateSchemaRejectsKbIdWithoutPartitionKey() {
        assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(kbField(false), vectorField(DIM)), DIM, COLLECTION));
    }

    @Test
    void validateSchemaRejectsDimensionMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(kbField(true), vectorField(768)), DIM, COLLECTION));

        assertTrue(ex.getMessage().contains("768"));
    }
}
