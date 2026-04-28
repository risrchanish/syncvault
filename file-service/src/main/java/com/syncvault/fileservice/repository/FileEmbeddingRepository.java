package com.syncvault.fileservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class FileEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public void save(UUID fileId, UUID userId, List<Double> embedding) {
        jdbcTemplate.update(
                "INSERT INTO file_embeddings (id, file_id, user_id, embedding) " +
                "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::vector)",
                fileId.toString(), userId.toString(), toVectorString(embedding)
        );
    }

    public List<EmbeddingSearchResult> findSimilar(UUID userId, List<Double> queryVector, int limit) {
        return jdbcTemplate.query(
                "SELECT file_id::text, 1-(embedding <=> ?::vector) AS score " +
                "FROM file_embeddings WHERE user_id = ?::uuid " +
                "ORDER BY score DESC LIMIT ?",
                (rs, rowNum) -> new EmbeddingSearchResult(
                        UUID.fromString(rs.getString("file_id")),
                        rs.getDouble("score")
                ),
                toVectorString(queryVector), userId.toString(), limit
        );
    }

    private String toVectorString(List<Double> vector) {
        return "[" + vector.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
    }
}
