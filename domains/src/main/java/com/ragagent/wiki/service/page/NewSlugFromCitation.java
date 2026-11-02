package com.ragagent.wiki.service.page;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * {@code WikiChunkCitationPrompt} 的 {@code "new_slugs"} 数组里的一项。
 *
 * <p>与 {@link com.ragagent.contract.wiki.ExtractedItem} 镜像，但多一个 {@code type} 标签——因为该 prompt
 * 把 entities 与 concepts 放在同一个数组里输出。</p>
 */

// 键序显式钉住：分量上的 @JsonInclude 会让 Jackson 2 把该属性提前，不能依赖声明序。
@JsonPropertyOrder({"type", "name", "slug", "aliases", "description", "details", "source_chunks"})
public record NewSlugFromCitation(
        String type,
        String name,
        String slug,
        List<String> aliases,
        String description,
        String details,
        @JsonProperty("source_chunks") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> sourceChunks) {

    /** sourceChunks 的数量（null 容忍） */
    public int sourceChunkCount() {
        return sourceChunks == null ? 0 : sourceChunks.size();
    }
}
