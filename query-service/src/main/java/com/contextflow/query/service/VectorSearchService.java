package com.contextflow.query.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.contextflow.query.dto.ChunkSearchResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Finds the top-k most semantically similar chunks for a tenant.
     * Uses pgvector's <=> cosine distance operator.
     * Joins document_chunks with documents to retrieve the source filename for citations.
     */
    public List<ChunkSearchResult> search(UUID tenantId, float[] queryVector, int topK) {
        String vectorLiteral = formatVector(queryVector);

        // Passing vectorLiteral three times: once for similarity calc, once for ordering,
        // and tenant_id is passed as a plain string because ::uuid cast is used inline.
        String sql = """
                SELECT dc.chunk_index,
                       dc.content,
                       COALESCE(dc.page_number, 1)                           AS page_number,
                       d.original_filename,
                       1 - (dc.embedding <=> CAST(? AS vector))              AS similarity
                FROM ingestion.document_chunks dc
                JOIN ingestion.documents d ON dc.document_id = d.id
                WHERE dc.tenant_id = CAST(? AS uuid)
                  AND d.tenant_id  = CAST(? AS uuid)
                  AND dc.embedding IS NOT NULL
                ORDER BY dc.embedding <=> CAST(? AS vector)
                LIMIT ?
                """;

        String tenantStr = tenantId.toString();
        List<ChunkSearchResult> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChunkSearchResult(
                        rs.getString("original_filename"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getInt("page_number"),
                        rs.getDouble("similarity")
                ),
                vectorLiteral, tenantStr, tenantStr, vectorLiteral, topK
        );

        log.info("Vector search for tenant {} returned {} chunks (topK={})", tenantId, results.size(), topK);
        return results;
    }

    private String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
